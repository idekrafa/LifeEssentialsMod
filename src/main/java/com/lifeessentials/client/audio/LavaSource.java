package com.lifeessentials.client.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sound.sampled.AudioInputStream;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.music.Track;
import com.sedmelluq.discord.lavaplayer.format.AudioPlayerInputStream;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;

/**
 * A {@link PcmSource} backed by LavaPlayer.
 *
 * <p>Slots in behind the same interface the ffmpeg pipeline used, so the mixer
 * in {@link SpeakerPlayback} is unchanged. Two things get materially better:
 * seeking is a real seek ({@link AudioTrack#setPosition}) rather than decoding
 * and discarding, and nothing shells out to a helper process.
 */
final class LavaSource implements PcmSource {
	/** Resolving a YouTube id can involve a couple of round trips. */
	private static final long LOAD_TIMEOUT_SECONDS = 30;
	/**
	 * How long a read waits for a frame before calling it the end of the track.
	 *
	 * <p>Silence is deliberately <em>not</em> provided: LavaPlayer terminates the
	 * frame buffer at the real end of a track, so a plain EOF arrives promptly and
	 * the existing "track ended" report still fires. The timeout only matters when
	 * the network stalls, and a stall this long is broken by any measure.
	 */
	private static final long STALL_TIMEOUT_MS = 10000;

	private final AudioPlayer player;
	private final AudioInputStream stream;
	private volatile String diagnostics = "";

	private LavaSource(AudioPlayer player, AudioInputStream stream) {
		this.player = player;
		this.stream = stream;
	}

	// ------------------------------------------------------------------ open

	static LavaSource open(Track track, long startMs) throws AudioSources.OpenFailure {
		AudioPlayerManager manager = LavaEngine.manager();
		if (manager == null) {
			throw new AudioSources.OpenFailure("The audio engine failed to start");
		}

		String identifier = identifierFor(track);
		AudioTrack loaded = load(manager, identifier, track);

		AudioPlayer player = manager.createPlayer();
		LavaSource source = new LavaSource(player,
				AudioPlayerInputStream.createStream(player, LavaEngine.format(), STALL_TIMEOUT_MS, false));
		player.addListener(source.new Events());
		player.playTrack(loaded);
		if (startMs > 0) {
			// a real seek — the container is indexed, so this costs a request, not
			// a decode of everything up to the offset
			loaded.setPosition(startMs);
		}
		return source;
	}

	/** Turns a library {@link Track} into something LavaPlayer can resolve. */
	private static String identifierFor(Track track) throws AudioSources.OpenFailure {
		return switch (track.source()) {
			case YOUTUBE -> "https://www.youtube.com/watch?v=" + track.uri();
			case URL -> track.uri();
			case FILE -> {
				Path path = MusicFolder.resolve(track.uri());
				if (path == null) {
					throw new AudioSources.OpenFailure("\"" + track.uri() + "\" isn't in your "
							+ MusicFolder.FOLDER_NAME + " folder");
				}
				yield path.toAbsolutePath().toString();
			}
			case SPOTIFY -> throw new AudioSources.OpenFailure(
					"Spotify tracks play through the desktop app");
		};
	}

	private static AudioTrack load(AudioPlayerManager manager, String identifier, Track track)
			throws AudioSources.OpenFailure {
		CompletableFuture<AudioTrack> result = new CompletableFuture<>();
		manager.loadItem(identifier, new AudioLoadResultHandler() {
			@Override
			public void trackLoaded(AudioTrack loaded) {
				result.complete(loaded);
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist) {
				// a shared YouTube link often carries a radio playlist; the track the
				// user actually pasted is the one we want
				AudioTrack selected = playlist.getSelectedTrack();
				if (selected == null && !playlist.getTracks().isEmpty()) {
					selected = playlist.getTracks().get(0);
				}
				if (selected == null) {
					result.completeExceptionally(
							new AudioSources.OpenFailure("That link has no playable audio"));
				} else {
					result.complete(selected);
				}
			}

			@Override
			public void noMatches() {
				result.completeExceptionally(
						new AudioSources.OpenFailure("Nothing playable was found at that link"));
			}

			@Override
			public void loadFailed(FriendlyException e) {
				result.completeExceptionally(new AudioSources.OpenFailure(friendly(e)));
			}
		});

		try {
			return result.get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			result.cancel(true);
			throw new AudioSources.OpenFailure("Timed out looking up \"" + track.displayTitle() + "\"");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AudioSources.OpenFailure("Interrupted");
		} catch (ExecutionException e) {
			if (e.getCause() instanceof AudioSources.OpenFailure failure) throw failure;
			LifeEssentials.LOGGER.warn("Could not load {}", identifier, e.getCause());
			throw new AudioSources.OpenFailure("Could not load that track");
		}
	}

	/**
	 * LavaPlayer marks messages it considers safe to show a user; anything else is
	 * an internal detail that would only confuse someone reading it in chat.
	 */
	private static String friendly(FriendlyException e) {
		if (e.severity == FriendlyException.Severity.COMMON && e.getMessage() != null) {
			return e.getMessage();
		}
		return "That track could not be loaded";
	}

	// ---------------------------------------------------------------- reading

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		int aligned = length - length % BYTES_PER_FRAME;
		if (aligned <= 0) return 0;
		return stream.read(buffer, offset, aligned);
	}

	@Override
	public String diagnostics() {
		return diagnostics;
	}

	@Override
	public void close() {
		try {
			stream.close();
		} catch (IOException ignored) {
			// the player is going away anyway
		}
		player.destroy();
	}

	/** Captures why a track died, so the speaker can say something useful. */
	private final class Events extends AudioEventAdapter {
		@Override
		public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException e) {
			diagnostics = friendly(e);
		}

		@Override
		public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
			diagnostics = "The stream stopped responding";
		}

		@Override
		public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason reason) {
			if (reason == AudioTrackEndReason.LOAD_FAILED && diagnostics.isEmpty()) {
				diagnostics = "The stream failed partway through";
			}
		}
	}
}
