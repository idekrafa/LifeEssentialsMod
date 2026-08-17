package com.lifeessentials.client.music;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs Windows PowerShell scripts on a single background thread — the Windows
 * counterpart of {@link AppleScriptRunner}. Scripts are passed via
 * {@code -EncodedCommand} (Base64 UTF-16LE) so quoting can never break them.
 * Targets Windows PowerShell 5.1 ({@code powershell.exe}) because its WinRT
 * projection is what the media-session scripts rely on.
 */
public final class PowerShellRunner {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "LifeEssentials-PowerShell");
		thread.setDaemon(true);
		return thread;
	});

	private PowerShellRunner() {
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public static void submit(Runnable task) {
		EXECUTOR.submit(task);
	}

	public static void fireAndForget(String script) {
		EXECUTOR.submit(() -> runBlocking(script));
	}

	/** Blocking — only call from the executor thread. Returns trimmed stdout or null. */
	public static String runBlocking(String script) {
		if (!isWindows()) return null;
		try {
			String encoded = Base64.getEncoder()
					.encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
			Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
					"-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).start();
			if (!process.waitFor(8, TimeUnit.SECONDS)) {
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

	/** Blocking — only call from the executor thread. Checks tasklist for an image name. */
	public static boolean processRunning(String imageName) {
		try {
			Process process = new ProcessBuilder("tasklist", "/FI",
					"IMAGENAME eq " + imageName, "/NH").start();
			if (!process.waitFor(4, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.toLowerCase().contains(imageName.toLowerCase())) {
						return true;
					}
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}

	/** Fire-and-forget arbitrary command (e.g. launching an app / URI). */
	public static void exec(String... command) {
		EXECUTOR.submit(() -> {
			try {
				new ProcessBuilder(command).start();
			} catch (Exception ignored) {
			}
		});
	}
}
