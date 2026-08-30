package com.lifeessentials.client.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.lifeessentials.LifeEssentials;
import net.minecraft.client.Minecraft;

/**
 * Finds the external helpers the speaker uses to decode audio.
 *
 * <p>{@code ffmpeg} turns anything (mp3, flac, m4a, opus, a live http stream)
 * into raw PCM; {@code yt-dlp} turns a YouTube link into a stream url. Both are
 * optional: without them the speaker still plays WAV/AIFF through Java's own
 * decoder. Drop the binaries in {@code <game dir>/lifeessentials/bin} or just
 * have them on PATH.
 */
public final class MediaTools {
	private static final long PROBE_TIMEOUT_SECONDS = 6;

	private static final Object PROBE_LOCK = new Object();

	private static volatile String ffmpegPath;
	private static volatile String ytDlpPath;
	private static volatile boolean probed;

	private MediaTools() {
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** Where players can drop ffmpeg / yt-dlp so the mod finds them without PATH. */
	public static Path binDirectory() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("lifeessentials").resolve("bin");
	}

	/**
	 * Blocks until the lookup has finished. Only ever call this off the render
	 * thread — running the candidates can take a couple of seconds.
	 */
	public static String ffmpeg() {
		awaitProbe();
		return ffmpegPath;
	}

	/** Blocking, like {@link #ffmpeg()}. */
	public static String ytDlp() {
		awaitProbe();
		return ytDlpPath;
	}

	public static boolean hasFfmpeg() {
		return ffmpeg() != null;
	}

	public static boolean hasYtDlp() {
		return ytDlp() != null;
	}

	/** Non-blocking — for UI that shouldn't stall waiting on the lookup. */
	public static boolean isProbed() {
		return probed;
	}

	/** Kicks the lookup off on a background thread so the first import is instant. */
	public static void probeAsync() {
		if (probed) return;
		startThread(MediaTools::awaitProbe);
	}

	/**
	 * Re-runs the lookup. Called when the import screen opens, so installing
	 * ffmpeg or yt-dlp while the game is running is picked up without a restart.
	 */
	public static void refreshAsync() {
		startThread(() -> {
			synchronized (PROBE_LOCK) {
				probed = false;
				awaitProbe();
			}
		});
	}

	private static void startThread(Runnable body) {
		Thread thread = new Thread(body, "life-essentials-media-probe");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Runs the lookup on the calling thread if nobody has yet, otherwise waits
	 * for whoever is already doing it. The old version fired an async probe and
	 * immediately returned null, so the very first YouTube import always claimed
	 * yt-dlp wasn't installed.
	 */
	private static void awaitProbe() {
		if (probed) return;
		synchronized (PROBE_LOCK) {
			if (probed) return;
			try {
				ffmpegPath = firstWorking("ffmpeg", "-version");
				ytDlpPath = firstWorking("yt-dlp", "--version");
				LifeEssentials.LOGGER.info("Speaker decoders — ffmpeg: {}, yt-dlp: {}",
						ffmpegPath == null ? "not found" : ffmpegPath,
						ytDlpPath == null ? "not found" : ytDlpPath);
			} catch (Exception e) {
				LifeEssentials.LOGGER.warn("Could not probe media tools", e);
			} finally {
				probed = true;
			}
		}
	}

	private static String firstWorking(String name, String versionFlag) {
		for (String candidate : candidates(name)) {
			if (runs(candidate, versionFlag)) return candidate;
		}
		return null;
	}

	private static List<String> candidates(String name) {
		String executable = isWindows() ? name + ".exe" : name;
		List<String> list = new ArrayList<>();
		String override = System.getProperty("lifeessentials." + name.replace("-", ""));
		if (override != null && !override.isBlank()) list.add(override);
		try {
			Path local = binDirectory().resolve(executable);
			if (Files.isRegularFile(local)) list.add(local.toAbsolutePath().toString());
		} catch (Exception ignored) {
			// game directory not available yet — PATH lookup below still works
		}
		list.add(executable);
		if (!isWindows()) {
			list.add("/opt/homebrew/bin/" + name);
			list.add("/usr/local/bin/" + name);
			list.add("/usr/bin/" + name);
			list.add("/snap/bin/" + name);
		}
		return list;
	}

	private static boolean runs(String executable, String versionFlag) {
		Process process = null;
		try {
			process = new ProcessBuilder(executable, versionFlag)
					.redirectErrorStream(true)
					.start();
			process.getOutputStream().close();
			drain(process.getInputStream());
			return process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			if (process != null && process.isAlive()) process.destroyForcibly();
		}
	}

	private static void drain(InputStream stream) throws IOException {
		try (stream) {
			byte[] buffer = new byte[4096];
			while (stream.read(buffer) >= 0) {
				// discard
			}
		}
	}

	/** Runs a command and returns its stdout, or {@code null} when it fails. */
	public static String capture(List<String> command, long timeoutSeconds) {
		Process process = null;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(false).start();
			process.getOutputStream().close();
			byte[] out;
			try (InputStream stream = process.getInputStream()) {
				out = stream.readAllBytes();
			}
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return null;
			if (process.exitValue() != 0) return null;
			return new String(out, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			if (process != null && process.isAlive()) process.destroyForcibly();
		}
	}

	/**
	 * Short, non-blocking one-liner for the narrow phone screen. Deliberately
	 * reads the fields directly so drawing a frame never waits on the lookup.
	 */
	public static String status() {
		if (!probed) return "checking decoders…";
		boolean ff = ffmpegPath != null;
		boolean yt = ytDlpPath != null;
		if (ff && yt) return "ffmpeg ok · yt-dlp ok";
		if (ff) return "ffmpeg ok · no yt-dlp";
		if (yt) return "no ffmpeg · yt-dlp ok";
		return "no ffmpeg or yt-dlp";
	}
}
