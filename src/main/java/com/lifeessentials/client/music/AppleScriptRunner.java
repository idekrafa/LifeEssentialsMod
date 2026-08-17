package com.lifeessentials.client.music;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs AppleScript through {@code osascript} on a single background thread so
 * the render thread never blocks on the media apps.
 *
 * The first control of Spotify / Music triggers macOS's one-time
 * "Minecraft wants to control ..." automation permission prompt.
 */
public final class AppleScriptRunner {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "LifeEssentials-AppleScript");
		thread.setDaemon(true);
		return thread;
	});

	private AppleScriptRunner() {
	}

	public static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	public static void submit(Runnable task) {
		EXECUTOR.submit(task);
	}

	public static void fireAndForget(String script) {
		EXECUTOR.submit(() -> runBlocking(script));
	}

	/** Blocking — only call from the executor thread. Returns trimmed stdout or null. */
	public static String runBlocking(String script) {
		if (!isMac()) return null;
		try {
			Process process = new ProcessBuilder("osascript", "-e", script).start();
			if (!process.waitFor(4, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return null;
			}
			if (process.exitValue() != 0) return null;
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					if (!sb.isEmpty()) sb.append('\n');
					sb.append(line);
				}
				return sb.toString().trim();
			}
		} catch (Exception e) {
			return null;
		}
	}

	/** Blocking — only call from the executor thread. */
	public static boolean processRunning(String processName) {
		try {
			Process process = new ProcessBuilder("pgrep", "-x", processName).start();
			return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
