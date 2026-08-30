package com.lifeessentials.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sound.sampled.AudioInputStream;

import com.lifeessentials.client.audio.AudioBackend;
import com.lifeessentials.client.audio.BackendFailure;
import com.lifeessentials.client.audio.PcmSource;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.format.AudioPlayerInputStream;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.youtube.YoutubeAudioSourceManager;

/**
 * LavaPlayer, on the far side of the classloader boundary.
 *
 * <p>Nothing here is referenced by name from the mod — {@link com.lifeessentials.client.audio.BackendLoader}
 * reflects this class into being once and then only ever talks to {@link AudioBackend}.
 * Keep it that way: the moment the mod imports a LavaPlayer type directly, the mod
 * jar carries those packages again and the whole isolation is undone.
 */
public final class LavaBackend implements AudioBackend {
	/** Identical to {@link PcmSource#FORMAT}: stereo, 48 kHz, signed 16-bit LE. */
	private static final Pcm16AudioDataFormat FORMAT =
			new Pcm16AudioDataFormat(2, PcmSource.FRAMES_PER_SECOND, 960, false);
	private static final int FRAME_BUFFER_MS = 1000;
	private static final long LOAD_TIMEOUT_SECONDS = 30;
	private static final long LOOKUP_TIMEOUT_SECONDS = 25;
	/** How long a read waits for a frame before it counts as the end of the track. */
	private static final long STALL_TIMEOUT_MS = 10000;

	private final AudioPlayerManager manager;

	public LavaBackend() {
		DefaultAudioPlayerManager built = new DefaultAudioPlayerManager();
		built.getConfiguration().setOutputFormat(FORMAT);
		built.setFrameBufferDuration(FRAME_BUFFER_MS);
		// LavaPlayer's own YouTube manager is unmaintained and no longer resolves;
		// dev.lavalink.youtube is the one that tracks YouTube's changes. Its default
		// client set is used deliberately so a version bump keeps working.
		built.registerSourceManager(new YoutubeAudioSourceManager(true));
		built.registerSourceManager(new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY));
		built.registerSourceManager(new LocalAudioSourceManager());
		this.manager = built;
	}

	// ------------------------------------------------------------------ playback

	@Override
	public PcmSource open(String identifier, long startMs) throws BackendFailure {
		AudioTrack track = load(identifier, LOAD_TIMEOUT_SECONDS);
		if (track == null) {
			throw new BackendFailure("Nothing playable was found at that link");
		}
		AudioPlayer player = manager.createPlayer();
		AudioInputStream stream =
				AudioPlayerInputStream.createStream(player, FORMAT, STALL_TIMEOUT_MS, false);
		LavaPcmSource source = new LavaPcmSource(player, stream);
		player.addListener(source.events());
		player.playTrack(track);
		if (startMs > 0) {
			// a real seek: the container is indexed, so this costs a request rather
			// than decoding everything up to the offset
			track.setPosition(startMs);
		}
		return source;
	}

	// ------------------------------------------------------------------ metadata

	@Override
	public List<String[]> resolve(String identifier, int limit) {
		try {
			List<AudioTrack> tracks = loadAll(identifier, limit, LOOKUP_TIMEOUT_SECONDS);
			List<String[]> out = new ArrayList<>(tracks.size());
			for (AudioTrack track : tracks) {
				AudioTrackInfo info = track.getInfo();
				// a live stream reports a nonsense length; leave it unknown so the
				// speaker waits for a listener's report instead of skipping on a timer
				long seconds = info.isStream ? 0 : track.getDuration() / 1000;
				out.add(new String[] {
					info.title == null ? "" : info.title,
					info.author == null ? "" : info.author,
					info.identifier == null ? identifier : info.identifier,
					Long.toString(seconds),
				});
			}
			return out;
		} catch (Exception e) {
			return List.of();
		}
	}

	@Override
	public void shutdown() {
		manager.shutdown();
	}

	// -------------------------------------------------------------------- loading

	private AudioTrack load(String identifier, long timeoutSeconds) throws BackendFailure {
		try {
			List<AudioTrack> tracks = loadAll(identifier, 1, timeoutSeconds);
			return tracks.isEmpty() ? null : tracks.get(0);
		} catch (BackendFailure e) {
			throw e;
		} catch (Exception e) {
			throw new BackendFailure("Could not load that track");
		}
	}

	private List<AudioTrack> loadAll(String identifier, int limit, long timeoutSeconds)
			throws BackendFailure, InterruptedException {
		CompletableFuture<List<AudioTrack>> result = new CompletableFuture<>();
		manager.loadItem(identifier, new AudioLoadResultHandler() {
			@Override
			public void trackLoaded(AudioTrack track) {
				result.complete(List.of(track));
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist) {
				// sharing from YouTube Music appends a radio playlist to the link;
				// the selected track is the song the player actually meant
				if (playlist.getSelectedTrack() != null) {
					result.complete(List.of(playlist.getSelectedTrack()));
					return;
				}
				List<AudioTrack> tracks = playlist.getTracks();
				result.complete(tracks.size() > limit ? tracks.subList(0, limit) : tracks);
			}

			@Override
			public void noMatches() {
				result.complete(List.of());
			}

			@Override
			public void loadFailed(FriendlyException e) {
				result.completeExceptionally(new BackendFailure(friendly(e)));
			}
		});

		try {
			return result.get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			result.cancel(true);
			throw new BackendFailure("Timed out looking that up");
		} catch (ExecutionException e) {
			if (e.getCause() instanceof BackendFailure failure) throw failure;
			throw new BackendFailure("Could not load that track");
		}
	}

	/**
	 * LavaPlayer marks which messages are safe to show a user; the rest are internal
	 * detail that would only confuse someone reading them in chat.
	 */
	static String friendly(FriendlyException e) {
		if (e.severity == FriendlyException.Severity.COMMON && e.getMessage() != null) {
			return e.getMessage();
		}
		return "That track could not be loaded";
	}
}
