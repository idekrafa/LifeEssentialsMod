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

`SpeakerPlayback` runs one daemon thread per audible speaker. The game thread
only ever writes two volatile gain fields, so a stalled network stream can never
hitch the render loop.

- **Attenuation** — full volume within 2 blocks, then a smooth roll-off to the
  speaker's configured range.
- **Panning** — constant-power stereo placement from the listener's facing.
  Minecraft yaw 0 looks down `+Z`, so forward is `(-sin yaw, cos yaw)` and the
  listener's right is `(-cos yaw, -sin yaw)`. Panning is capped at 0.75 so a
  speaker off to one side still leaves signal in the far ear.
- **Gain ramping** — gains interpolate across each chunk rather than snapping, so
  walking around a speaker doesn't produce zipper noise.
- **Ducking** — `AudioEngine.audibleGain()` feeds `MusicClientState`, which drives
  the existing `VolumeDucker`. Game audio ducks under the speaker exactly as it
  already did under phone music.

`stereoGains(dx, dz, yaw, gain, distance)` is package-visible specifically so the
geometry can be tested without a running game.

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
