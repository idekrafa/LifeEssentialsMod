package com.lifeessentials.client.audio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackSource;
import com.lifeessentials.music.TrackUris;

/** Turns a library {@link Track} into decoded PCM, seeked to the right offset. */
public final class AudioSources {
	private static final int CONNECT_TIMEOUT_MS = 10000;
	private static final int READ_TIMEOUT_MS = 20000;

	/**
	 * Format preference for the yt-dlp fallback.
	 *
	 * <p>An .m4a is an MP4 whose index can sit at the end of the file, and down a
	 * pipe there is no seeking back to it — ffmpeg intermittently rejects the stream
	 * outright. WebM is a streaming container and decodes from the first byte, and
	 * its Opus audio is already 48 kHz, so nothing is resampled.
	 */
	private static final String YTDLP_AUDIO_FORMAT =
			"bestaudio[ext=webm]/bestaudio[acodec=opus]/bestaudio/best";

	/** Why a track couldn't be opened — shown once per track in chat. */
	public static class OpenFailure extends Exception {
		public OpenFailure(String message) {
			super(message);
		}
	}

	private AudioSources() {
	}

	/**
	 * Opens a track, preferring the in-process engine.
	 *
	 * <p>LavaPlayer needs nothing installed and does a real seek, so it is always
	 * the first choice. The ffmpeg/yt-dlp path below stays as a fallback for a
	 * platform whose natives won't load — losing audio entirely because a decoder
	 * didn't link is a much worse outcome than shelling out.
	 */
	public static PcmSource open(Track track, long startMs) throws OpenFailure {
		if (track.source() == TrackSource.SPOTIFY) {
			throw new OpenFailure("Spotify tracks play through the desktop app");
		}
		AudioBackend backend = BackendLoader.backend();
		if (backend != null) {
			try {
				return backend.open(identifierFor(track), startMs);
			} catch (BackendFailure e) {
				throw new OpenFailure(e.getMessage());
			}
		}
		return openExternal(track, startMs);
	}

	/** Turns a library {@link Track} into something the engine can resolve. */
	private static String identifierFor(Track track) throws OpenFailure {
		return switch (track.source()) {
			case YOUTUBE -> "https://www.youtube.com/watch?v=" + track.uri();
			case URL -> track.uri();
			case FILE -> {
				Path path = MusicFolder.resolve(track.uri());
				if (path == null) {
					throw new OpenFailure("\"" + track.uri() + "\" isn't in your "
							+ MusicFolder.FOLDER_NAME + " folder");
				}
				yield path.toAbsolutePath().toString();
			}
			case SPOTIFY -> throw new OpenFailure("Spotify tracks play through the desktop app");
		};
	}

	/** The pre-2.0 path: ffmpeg for everything, plus yt-dlp for YouTube. */
	private static PcmSource openExternal(Track track, long startMs) throws OpenFailure {
		return switch (track.source()) {
			case FILE -> openFile(track, startMs);
			case URL -> openUrl(track.uri(), startMs);
			case YOUTUBE -> openYoutube(track, startMs);
			case SPOTIFY -> throw new OpenFailure("Spotify tracks play through the desktop app");
		};
	}

	private static PcmSource openFile(Track track, long startMs) throws OpenFailure {
		Path path = MusicFolder.resolve(track.uri());
		if (path == null) {
			throw new OpenFailure("\"" + track.uri() + "\" isn't in your "
					+ MusicFolder.FOLDER_NAME + " folder");
		}
		if (MediaTools.hasFfmpeg()) {
			return ffmpeg(path.toAbsolutePath().toString(), startMs, false);
		}
		if (!track.uri().toLowerCase(Locale.ROOT).endsWith(".wav")) {
			throw new OpenFailure("Install ffmpeg to play " + track.uri());
		}
		try {
			return javaSound(new BufferedInputStream(java.nio.file.Files.newInputStream(path)), startMs);
		} catch (IOException e) {
			throw new OpenFailure("Could not read " + track.uri());
		}
	}

	private static PcmSource openUrl(String url, long startMs) throws OpenFailure {
		if (!TrackUris.isHttpUrl(url)) {
			throw new OpenFailure("Only http(s) links can be streamed");
		}
		if (MediaTools.hasFfmpeg()) {
			return ffmpeg(url, startMs, true);
		}
		try {
			return javaSound(new BufferedInputStream(openHttp(url)), startMs);
		} catch (IOException e) {
			throw new OpenFailure("Install ffmpeg to stream this link");
		}
	}

	/**
	 * Streams a YouTube video by piping yt-dlp's download straight into ffmpeg.
	 *
	 * <p>The obvious approach — ask yt-dlp for the media url and hand that to
	 * ffmpeg — does not work reliably. YouTube mints those urls against the
	 * player client yt-dlp impersonated, and the headers it reports do not match
	 * that client, so fetching the url yourself returns 403 much of the time.
	 * Letting yt-dlp do the fetching sidesteps the whole problem.
	 *
	 * <p>The cost is that the seek has to be an ffmpeg <em>output</em> option: a
	 * pipe can't be seeked, so joining mid-track decodes and discards up to that
	 * point. Decoding runs far faster than realtime and {@code catchUp} squares
	 * up whatever time it took.
	 */
	private static PcmSource openYoutube(Track track, long startMs) throws OpenFailure {
		if (!MediaTools.hasYtDlp()) {
			throw new OpenFailure("YouTube needs yt-dlp — see the mod's README");
		}
		if (!MediaTools.hasFfmpeg()) {
			throw new OpenFailure("YouTube needs ffmpeg — see the mod's README");
		}

		List<String> fetch = List.of(MediaTools.ytDlp(),
				"--no-warnings", "--no-playlist", "--no-progress",
				// YouTube throttles bursts, and a speaker starting a track has every
				// listener in range fetch at once; let yt-dlp back off on its own too
				"--retries", "5", "--fragment-retries", "5", "--retry-sleep", "2",
				"--socket-timeout", "15",
				"-f", YTDLP_AUDIO_FORMAT, "-o", "-",
				"https://www.youtube.com/watch?v=" + track.uri());

		List<String> decode = new ArrayList<>(List.of(MediaTools.ffmpeg(),
				"-hide_banner", "-loglevel", "error", "-nostdin", "-i", "pipe:0",
				"-vn", "-sn", "-dn"));
		if (startMs > 0) {
			decode.add("-ss"); // after -i on purpose: output seeking, the only kind a pipe allows
			decode.add(String.format(Locale.ROOT, "%.3f", startMs / 1000.0));
		}
		decode.addAll(List.of("-f", "s16le", "-acodec", "pcm_s16le",
				"-ar", String.valueOf(PcmSource.FRAMES_PER_SECOND), "-ac", "2", "-"));

		try {
			List<Process> pipeline = ProcessBuilder.startPipeline(
					List.of(new ProcessBuilder(fetch), new ProcessBuilder(decode)));
			Process last = pipeline.get(pipeline.size() - 1);
			return new PcmSource.Of(
					new BufferedInputStream(last.getInputStream(), 1 << 16), pipeline);
		} catch (IOException e) {
			LifeEssentials.LOGGER.warn("Could not start the yt-dlp/ffmpeg pipeline", e);
			throw new OpenFailure("Could not start yt-dlp");
		}
	}

	// ---------------------------------------------------------------- ffmpeg

	private static PcmSource ffmpeg(String input, long startMs, boolean network) throws OpenFailure {
		List<String> command = new ArrayList<>();
		command.add(MediaTools.ffmpeg());
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");
		command.add("-nostdin");
		if (network) {
			// long tracks over http drop out otherwise
			command.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1",
					"-reconnect_delay_max", "5"));
		}
		if (startMs > 0) {
			command.add("-ss");
			command.add(String.format(Locale.ROOT, "%.3f", startMs / 1000.0));
		}
		command.addAll(List.of("-i", input, "-vn", "-sn", "-dn",
				"-f", "s16le", "-acodec", "pcm_s16le",
				"-ar", String.valueOf(PcmSource.FRAMES_PER_SECOND), "-ac", "2", "-"));
		try {
			Process process = new ProcessBuilder(command)
					.redirectErrorStream(false)
					.start();
			process.getOutputStream().close();
			return new PcmSource.Of(new BufferedInputStream(process.getInputStream(), 1 << 16), process);
		} catch (IOException e) {
			LifeEssentials.LOGGER.warn("ffmpeg failed to start", e);
			throw new OpenFailure("ffmpeg could not start");
		}
	}

	// ------------------------------------------------------------- javax path

	/** Fallback decoder — handles WAV/AIFF/AU without any external tool. */
	private static PcmSource javaSound(InputStream raw, long startMs) throws OpenFailure {
		try {
			AudioInputStream encoded = AudioSystem.getAudioInputStream(raw);
			AudioInputStream pcm = AudioSystem.getAudioInputStream(PcmSource.FORMAT, encoded);
			skip(pcm, startMs * PcmSource.BYTES_PER_SECOND / 1000);
			return new PcmSource.Of(pcm, List.of());
		} catch (UnsupportedAudioFileException e) {
			closeQuietly(raw);
			throw new OpenFailure("Install ffmpeg to play this format");
		} catch (IllegalArgumentException | IOException e) {
			closeQuietly(raw);
			throw new OpenFailure("Could not decode this track");
		}
	}

	private static void skip(InputStream stream, long bytes) throws IOException {
		long remaining = bytes - bytes % PcmSource.BYTES_PER_FRAME;
		while (remaining > 0) {
			long skipped = stream.skip(remaining);
			if (skipped <= 0) return; // shorter than the seek point; play from here
			remaining -= skipped;
		}
	}

	private static InputStream openHttp(String url) throws IOException {
		URLConnection connection = URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(READ_TIMEOUT_MS);
		connection.setRequestProperty("User-Agent", "LifeEssentials/1.2 (Minecraft mod)");
		if (connection instanceof HttpURLConnection http) {
			http.setInstanceFollowRedirects(true);
		}
		return connection.getInputStream();
	}

	private static void closeQuietly(InputStream stream) {
		try {
			stream.close();
		} catch (IOException ignored) {
			// nothing useful to do
		}
	}
}
