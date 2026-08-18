# Architecture

How Life Essentials is put together, and — more usefully — *why* the awkward parts
are the way they are. Written for whoever next has to change this.

The mod is two loosely-coupled features sharing one item: the **phone** (texting,
desktop media control) and the **JBL speaker** (real audio playback synchronised
across a server). This document is mostly about the speaker, because that's where
the non-obvious engineering lives.

---

## 1. The central idea: the server never sends audio

A speaker plays a track and everyone in range hears the same thing at the same
moment. The obvious implementation — the server streams PCM to every client — is
also the wrong one: it would cost megabits per listener and put decoding on the
one machine least able to spare it.

Instead the server broadcasts only **which track, and how far into it**. Every
client decodes its own copy locally and starts at that offset:

```
                    SpeakerBlockEntity  (authoritative)
                    playlist · index · playing · anchor
                                 │
                     SpeakerSyncS2C  ~5s while playing
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
                 client A     client B     client C
                    │            │            │
              own decoder   own decoder   own decoder
                    └──────── same second ───┘
```

Consequences worth knowing:

- Network cost is a few dozen bytes per speaker per five seconds, regardless of
  how many people are listening.
- A dedicated server needs no audio tooling at all. It never touches ffmpeg,
  yt-dlp, or a sound device.
- Clients must independently be *able* to decode a track. One player missing a
  local file just hears silence for it; everyone else is unaffected.

### Staying in sync without synchronised clocks

`SpeakerSyncS2C` carries `positionMs` — where the track was **at the moment the
packet was built**. The client records its own `System.currentTimeMillis()` on
arrival and extrapolates from there (`ClientSpeakers.View#positionMs`). Nobody
needs a shared clock; the error is one packet's latency.

`AudioEngine` compares that expectation against what the mixer has actually
played (`SpeakerPlayback#positionMs`, derived from frames written minus frames
still buffered). Drift beyond **2.5 s** restarts the decoder at the correct
offset. Two guards stop that from becoming a restart loop:

1. Drift is not evaluated until the stream has produced real audio
   (`hasOutput()`), because a stream that is still opening is trivially "behind".
2. On open, `SpeakerPlayback#catchUp` **discards** however much audio elapsed
   while the stream was opening, so playback joins level with the room instead of
   starting late and being yanked backwards forever. The target is recomputed
   each pass rather than fixed up front — with a piped source most of the delay
   lands on the first read, not on the open — and it converges because decoding
   outruns realtime by a wide margin.

### Advancing the queue

Whoever knows the track length decides. If `Track#durationSeconds` is known, the
block entity advances on its own clock. If not, the first client whose decoder
hits EOF reports `SpeakerTrackEndedC2S` and the server advances once, debounced
for 3 s so that every listener reporting at the same moment doesn't skip the
whole playlist. A 30-minute backstop covers a track nobody can decode at all.

---

## 2. Getting audio out of a file or a link

`AudioSources.open(track, startMs)` returns a `PcmSource` — decoded
**48 kHz, 16-bit, stereo, little-endian**, the one format everything normalises
to, seeked to the right offset.

Since 2.0 that decoding happens **in-process**, via LavaPlayer. `FILE`, `URL` and
`YOUTUBE` all go through `LavaSource`; `SPOTIFY` is never decoded and is handed to
the listener's desktop app as before.

### The engine (`LavaEngine` / `LavaSource`)

One `DefaultAudioPlayerManager` per client, output format
`Pcm16AudioDataFormat(2, 48000, 960, false)` — deliberately identical to
`PcmSource.FORMAT`, so nothing resamples on our side. `AudioPlayerInputStream`
turns the player into an ordinary `AudioInputStream`, which is why `LavaSource`
drops in behind the existing `PcmSource` interface and `SpeakerPlayback` did not
have to change at all.

What this bought over the old subprocess pipeline:

- **Nothing to install.** Previously every listener needed ffmpeg, and YouTube
  additionally needed yt-dlp, or they heard silence.
- **Real seeking.** `AudioTrack#setPosition` seeks the container instead of
  decoding and discarding everything up to the offset.
- **No thundering herd.** A speaker starting a track no longer makes every client
  in range spawn two processes and hit YouTube at the same instant.

Measured on an M-series Mac against the shaded jar with no external tools present:
~3.8 s to first audio (YouTube cipher solve plus the seek) and **~476× realtime**
once running. That startup cost is exactly what `SpeakerPlayback#catchUp` already
existed to absorb.

`LavaEngine.manager()` returns `null` rather than throwing when it cannot start, and
`AudioSources.open` then falls back to the ffmpeg path below — losing audio entirely
because a native failed to link is a far worse outcome than shelling out.

> **Do not relocate `com.sedmelluq.discord.lavaplayer.natives`.** JNI symbol names are
> mangled from the Java class name, so `libconnector` exports
> `Java_com_sedmelluq_discord_lavaplayer_natives_…`. Shading that package renames the
> lookup and every native call dies with `UnsatisfiedLinkError`. `build.gradle`
> relocates all of LavaPlayer *except* that one package; Iam Music Player ships the
> identical split for the identical reason.
>
> **That makes us mutually exclusive with Iam Music Player, and with any other mod
> that bundles LavaPlayer the same way.** NeoForge builds each mod jar as a JPMS
> module, and two modules exporting the same package is a hard resolution failure —
> not a first-one-wins classloader situation. The seven
> `com.sedmelluq.discord.lavaplayer.natives.*` packages collide and the game refuses
> to start. There is no build-side escape: relocating them breaks JNI, and not
> relocating them collides. Only one such mod can be installed at a time.
>
> **Borrowing the other mod's copy does not work either** — this was tried. Dropping
> the package from our jar clears the module conflict (verified: zero overlapping
> packages), but their JNI classes are welded to *their* relocated hierarchy:
>
> ```
> ours: OpusDecoder extends com.sedmelluq.lava.common.natives.NativeResourceHolder
> IMP:  OpusDecoder extends dev.felnull.imp.include.…lava.common.natives.NativeResourceHolder
> ```
>
> 14 of the 21 JNI classes match on signature; the 7 that touch the base class do
> not. Linking against them yields `IncompatibleClassChangeError`, which `LavaEngine`
> catches and turns into a silent fallback to ffmpeg — the worst outcome, since
> nobody has ffmpeg installed any more.
>
> The remaining real fix is to stop shipping LavaPlayer as *classes* at all: embed
> the jars as resources (resources declare no packages, so no module conflict),
> extract them at runtime, and load them in a child `URLClassLoader` whose parent is
> the mod's own loader. Class names stay original so JNI resolves, and the native
> binary must extract to a directory distinct from the other mod's or `System.load`
> refuses the second copy.
>
> Two more shading facts: httpclient is pinned to **4.5.13** because Minecraft declares
> that version `strictly` and LavaPlayer's requested 4.5.14 cannot co-resolve with it;
> and slf4j is *excluded* rather than relocated, because Minecraft already provides it.

Dev runs do not use the shaded jar, and ModDevGradle builds a run's classpath from its
own `additionalRuntimeClasspath` configuration rather than from `runtimeClasspath`. If
`runClient` boots and then throws `NoClassDefFoundError` on a LavaPlayer class, that
configuration is what's missing — compiling against the dependency is not enough.

### The fallback path

| Source | Path |
| :-- | :-- |
| `FILE` | ffmpeg on the file; falls back to `javax.sound` for `.wav` |
| `URL` | ffmpeg on the url; falls back to `javax.sound` |
| `YOUTUBE` | `yt-dlp \| ffmpeg` pipeline (see below) |
| `SPOTIFY` | never decoded — handed to the listener's desktop Spotify |

### Why external tools at all

Java decodes **WAVE, AU and AIFF only**. Not mp3, not ogg, not flac. Minecraft's
own sound engine is no help either: it plays registered sound events from
resource packs, not arbitrary files. So compressed audio needs a decoder from
somewhere, and ffmpeg covers every format uniformly.

They are needed **only on clients that want to hear the audio**, and they are
discovered at runtime by `MediaTools` — from an explicit system property, from
`<game dir>/lifeessentials/bin/`, from `PATH`, or from the usual Homebrew and
`/usr/local` locations.

> `MediaTools.ffmpeg()` and `ytDlp()` **block** until the lookup finishes. Call
> them from background threads only. UI uses the non-blocking `status()` and
> `isProbed()`. An earlier version fired the lookup asynchronously and returned
> `null` immediately, which made the first YouTube import of every session claim
> yt-dlp wasn't installed.

### Why YouTube is a pipeline, not a URL

The intuitive approach is to ask yt-dlp for a media url (`-g`, or `url` out of
`-j`) and hand that to ffmpeg. **This does not work reliably.** YouTube mints
those urls against whichever player client yt-dlp impersonated, and the
`http_headers` yt-dlp reports do not match that client — observed in practice as
a `c=ANDROID_VR` url paired with a desktop-Firefox User-Agent. Fetching it
yourself returns `403 Forbidden` much of the time, intermittently enough to look
like flakiness rather than a design problem.

So playback lets yt-dlp do the fetching, and pipes its output into ffmpeg via
`ProcessBuilder.startPipeline`. There is nothing left to mismatch.

Two consequences fall out of that choice:

- **Format matters.** `.m4a` is MP4, whose index can sit at the end of the file,
  and a pipe cannot seek backwards to it — ffmpeg intermittently rejects the
  stream with "Invalid data found". `YoutubeResolver.AUDIO_FORMAT` therefore
  prefers **WebM/Opus**, which is a streaming container and decodes from the
  first byte. Its Opus audio is 48 kHz, which is exactly what the mixer wants.
- **Seeking is an ffmpeg *output* option** (`-ss` after `-i`), the only kind a
  pipe allows. Joining mid-track decodes and discards up to that point;
  `catchUp` then squares up the time it took.

YouTube also rate-limits bursts of downloads per IP, and a speaker starting a
track asks every listener in range to fetch at the same moment. `SpeakerPlayback`
retries 3× at 3 s intervals, and yt-dlp is given its own `--retries`/`--retry-sleep`.

### Diagnostics

`PcmSource.Of` drains every process's stderr into a capped ring buffer. This is
not optional comfort: without it a failing decoder produces silence and nothing
else, anywhere. `diagnostics()` deliberately prefers a line containing `ERROR:`
over the last line, because in a `yt-dlp | ffmpeg` pipeline the upstream failure
is the real cause and the downstream one is noise — a yt-dlp 403 makes ffmpeg
report "Invalid data found", which sends you hunting in entirely the wrong place.

---

## 3. Mixing and placement

Since 2.0 the speaker is an **OpenAL source in Minecraft's own context**
(`AlSpeakerSource`), positioned at the block. It is not mixed by us at all.

Before, playback opened a `javax.sound.sampled` line — a second, separate audio
device that bypassed Minecraft's mixer. Everything spatial therefore had to be
faked: distance roll-off by hand, and stereo panning computed from the player's
yaw, with a cap so a speaker off to one side kept some signal in the far ear.
All of that is deleted. What replaced it:

- **Attenuation and panning** — OpenAL, against the listener transform the game
  already maintains. `AL_LINEAR_DISTANCE` with `AL_MAX_DISTANCE` set to the
  speaker's range, `AL_ROLLOFF_FACTOR` 1 and `AL_REFERENCE_DISTANCE` 0 — the
  identical settings vanilla uses in `Channel#linearAttenuation`, so the speaker
  falls off like any other block sound. Per-source distance models need
  `AL_SOFT_source_distance_model`, which Minecraft enables when it starts its
  sound library.
- **Occlusion and reverb** — free, and the real prize. Mods that hook OpenAL
  sources (Sound Physics Remastered) now treat the speaker as a world sound, so
  it muffles through walls and echoes in caves. The old line could never do this.
- **Ducking** — unchanged in behaviour. `AudioEngine.audibleGain()` still feeds
  `VolumeDucker`, but it now computes the *perceived* loudness itself (source gain
  × distance attenuation) purely for the duck, because the gain handed to OpenAL is
  the undistanced one.

### Threading

OpenAL is not thread-safe and the context is shared with the game, so the rule is:
**every AL call happens on the client thread.** `SpeakerPlayback` is split to honour
that — a daemon thread only decodes, and hands finished byte arrays to a bounded
`ArrayBlockingQueue`; `clientTick` drains that queue into AL buffers, recycles
whatever the hardware finished with, and updates position and gain.

The queue bound is load-bearing in a way it wasn't before: LavaPlayer decodes
hundreds of times faster than realtime, so without back-pressure it would buffer an
entire song into memory in a second or two. The decode thread blocks on `offer`.

Two consequences to keep in mind when changing this:

- A paused speaker never reaches `clientTick`, so `pauseAudio()` exists to stop the
  source explicitly. Forget it and a paused speaker keeps playing what is queued.
- Position now comes from the hardware — buffers retired plus `AL_SAMPLE_OFFSET`
  into what is still queued — rather than from bytes written to a line.

---

## 4. The library and the phone

`MusicLibraryState` (a vanilla `SavedData`) holds every imported `Track` and the
`Playlist`s built from them. It lives in the world save, so playlists survive
restarts and everyone on the server sees the same ones.

Import happens **entirely on the importing client** — `TrackImporter` runs the
yt-dlp metadata lookup and sends finished `Track`s to the server. That is why a
server never needs yt-dlp installed.

The whole library is re-sent to every client on any change
(`LibrarySyncS2C`), which is simple and cheap at realistic sizes. `MAX_TRACKS`
(750) and `MAX_PLAYLISTS` (60) exist to keep that packet under the 1 MB payload
ceiling *even if every entry is maximum length* — worst case is roughly 420 KB.
Raise them and do the arithmetic again.

### Trust boundary

Track data arrives over the network, so `TrackUris.sanitize` runs on the
**server** before anything enters the library:

- `FILE` names must be relative, forward-slashed, inside the music folder, with
  an audio extension. `../`, absolute paths, drive letters and backslashes are
  rejected. `MusicFolder.resolve` independently re-checks this on the client and
  returns `null` for anything that escapes the folder after normalisation.
- `URL` must be `http`/`https` with a real host. No `file:`, no other protocols.
- `YOUTUBE` must be a bare video id; `SPOTIFY` is normalised to `spotify:track:…`.
- Control characters are stripped from titles and artists.

Note the residual risk that is inherent to the feature rather than a bug: a
playlist *link* is a url your client will fetch. Treat a stranger's server's
playlists the way you'd treat any link they send you.

Deleting or renaming a playlist is restricted to its creator (or an operator);
adding and reordering are open, because a shared library is the point.

---

## 5. Package map

```
com.lifeessentials
├── ModBlocks / ModItems / ModComponents   registration
├── block/
│   ├── JblSpeakerBlock                    block, redstone edge, comparator out
│   ├── SpeakerBlockEntity                 authoritative playback state
│   └── ServerSpeakerManager               commands, validation, sync fan-out
├── music/                                 SHARED — both sides
│   ├── Track / TrackSource / Playlist     data + NBT + stream codecs
│   ├── MusicLibraryState                  SavedData, world-persistent
│   └── TrackUris                          parsing + the trust boundary
├── net/
│   ├── ModPayloads                        phone/texting/airpods (pre-existing)
│   └── MusicPayloads                      library + speaker packets
├── phone/                                 texting, numbers (pre-existing)
└── client/                                CLIENT ONLY
    ├── ClientMusicLibrary / ClientSpeakers  synced caches
    ├── audio/
    │   ├── AudioEngine                    decides what should be playing
    │   ├── SpeakerPlayback                one decode+mix thread per speaker
    │   ├── AudioSources                   Track -> PcmSource
    │   ├── PcmSource                      decoded stream + diagnostics
    │   ├── MediaTools                     ffmpeg / yt-dlp discovery
    │   ├── YoutubeResolver                yt-dlp metadata + format choice
    │   ├── TrackImporter                  what the user typed -> Tracks
    │   └── MusicFolder                    the shared music directory
    ├── gui/                               phone screens + the speaker deck
    └── music/                             desktop Spotify/Apple Music control
```

**Side safety.** Client-only code is never referenced from common code except
through a guarded indirection — `PhoneClientOpener`, `SpeakerClientOpener`, and
the `ClientPayloadHandler` / `ClientMusicPayloadHandler` split. Payload
registration lives in common code but the handler *bodies* live in client
classes, so a dedicated server never loads them.

---

## 6. Assets

Every texture is generated pixel art — `python3 scripts/gen_textures.py` rewrites
all of them from source. No Apple, Spotify or JBL artwork is shipped. Editing a
texture means editing the script, not the PNG.

The speaker is an 11-element block model (`jbl_speaker_base.json`) with a carry
handle, two end radiators, a control pad and rubber feet. Face UVs are
deliberately **omitted** so Minecraft derives them from element bounds — hand-
written UVs on a model this shape are a reliable source of silent mistakes. The
`playing` blockstate swaps to a model whose radiators use the lit texture.

---

## 7. Testing

There is no JUnit harness in the repo. What exists, and what it caught:

- **Logic** — link parsing across every YouTube/Spotify shape, path-traversal
  rejection, playlist reordering, NBT round-trips. Caught imported tracks being
  silently discarded (they carry no id until the server assigns one, and the
  emptiness check was testing the id).
- **Audio** — that the JVM really can resample 44.1 kHz mono to 48 kHz stereo,
  that seek-by-skip is sample-accurate, and that panning is correct in all four
  directions with constant power.
- **Assets** — every model/texture/blockstate reference resolves, element bounds
  are sane, and no GUI text band collides with another.
- **Live** — resolve → download → decode against real YouTube.

If you change the audio path, run something end-to-end against a real track. The
three worst bugs in this feature's history (the 403 url mismatch, MP4-over-pipe,
and the async probe) were all invisible to static reasoning and to the build.

**Building** needs `JAVA_HOME` exported — Homebrew's `openjdk@21` is keg-only, so
`./gradlew` cannot even start without it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew build
```
