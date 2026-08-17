package com.lifeessentials.client.audio;

import com.lifeessentials.LifeEssentials;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;

/**
 * The decoder, in-process.
 *
 * <p>This replaces the {@code yt-dlp | ffmpeg} subprocess pipeline. LavaPlayer
 * resolves and decodes inside the JVM — including solving YouTube's signature
 * ciphers with the bundled Rhino — so a listener needs no external tools, and a
 * speaker starting a track no longer has every client in range shell out to
 * YouTube at the same instant.
 *
 * <p>Everything here is <em>client only</em>: a dedicated server never decodes.
 */
public final class LavaEngine {
	/**
	 * Matches {@link PcmSource#FORMAT} exactly — stereo, 48 kHz, signed 16-bit,
	 * little-endian. 960 samples is 20 ms, LavaPlayer's natural chunk.
	 */
	private static final AudioDataFormat FORMAT = new Pcm16AudioDataFormat(
			2, PcmSource.FRAMES_PER_SECOND, 960, false);

	/** How much audio LavaPlayer decodes ahead. Covers a network hiccup. */
	private static final int FRAME_BUFFER_MS = 1000;

	private static AudioPlayerManager manager;
	private static String failure;
	private static boolean tried;

	private LavaEngine() {
	}

	public static AudioDataFormat format() {
		return FORMAT;
	}

	/** True when the engine loaded; false means fall back to the external tools. */
	public static synchronized boolean isAvailable() {
		return manager() != null;
	}

	/** Why the engine is unusable, or an empty string when it is fine. */
	public static synchronized String unavailableReason() {
		manager();
		return failure == null ? "" : failure;
	}

	/**
	 * Builds the player manager once, on first use.
	 *
	 * <p>Returns {@code null} rather than throwing: a machine where the natives
	 * won't load should quietly fall back to ffmpeg, not lose audio entirely.
	 */
	static synchronized AudioPlayerManager manager() {
		if (tried) return manager;
		tried = true;
		try {
			DefaultAudioPlayerManager built = new DefaultAudioPlayerManager();
			built.getConfiguration().setOutputFormat(FORMAT);
			built.setFrameBufferDuration(FRAME_BUFFER_MS);

			// The YouTube manager that ships inside LavaPlayer is unmaintained and
			// no longer resolves; dev.lavalink.youtube is the one that tracks
			// YouTube's changes. Its default client set is deliberately used rather
			// than a hand-picked list, so a version bump keeps working.
			built.registerSourceManager(new YoutubeAudioSourceManager(true));
			built.registerSourceManager(new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY));
			built.registerSourceManager(new LocalAudioSourceManager());

			manager = built;
			LifeEssentials.LOGGER.info("LavaPlayer audio engine ready");
		} catch (LinkageError | RuntimeException e) {
			// missing natives for this platform, or a shading mistake
			failure = e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : ": " + e.getMessage());
			LifeEssentials.LOGGER.warn("LavaPlayer unavailable, falling back to ffmpeg", e);
		}
		return manager;
	}

	public static synchronized void shutdown() {
		if (manager != null) {
			manager.shutdown();
			manager = null;
		}
		tried = false;
		failure = null;
	}
}
