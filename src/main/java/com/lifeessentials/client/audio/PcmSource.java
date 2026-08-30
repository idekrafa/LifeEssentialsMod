package com.lifeessentials.client.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.sound.sampled.AudioFormat;

/** A stream of decoded PCM in {@link #FORMAT}, ready to hand to a mixer line. */
public interface PcmSource extends AutoCloseable {
	/** What every decoder in this package normalises to. */
	AudioFormat FORMAT = new AudioFormat(48000.0F, 16, 2, true, false);

	int BYTES_PER_FRAME = 4;
	int FRAMES_PER_SECOND = 48000;
	int BYTES_PER_SECOND = FRAMES_PER_SECOND * BYTES_PER_FRAME;

	/** Returns the number of bytes read, or -1 at the end of the track. */
	int read(byte[] buffer, int offset, int length) throws IOException;

	@Override
	void close();

	/** What the helper process complained about, when it produced no audio. */
	default String diagnostics() {
		return "";
	}

	/** Wraps a decoded stream, killing the helper process (if any) on close. */
	final class Of implements PcmSource {
		/** Plenty for the last couple of ffmpeg error lines. */
		private static final int MAX_DIAGNOSTICS = 600;

		private final InputStream stream;
		private final List<Process> processes;
		private final StringBuilder errorTail = new StringBuilder();

		public Of(InputStream stream, Process process) {
			this(stream, process == null ? List.of() : List.of(process));
		}

		/** For a pipeline: every stage's stderr is drained and killed together. */
		public Of(InputStream stream, List<Process> processes) {
			this.stream = stream;
			this.processes = processes;
			for (Process process : processes) {
				pumpErrorStream(process);
			}
		}

		/**
		 * Keeps the tail of the helper's stderr. Without this a failing ffmpeg
		 * just yields silence with no explanation anywhere — which is exactly
		 * how a broken stream url used to present itself.
		 */
		private void pumpErrorStream(Process process) {
			Thread thread = new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						if (line.isBlank()) continue;
						synchronized (errorTail) {
							errorTail.append(line).append('\n');
							while (errorTail.length() > MAX_DIAGNOSTICS) {
								int cut = errorTail.indexOf("\n");
								errorTail.delete(0, cut < 0 ? errorTail.length() : cut + 1);
							}
						}
					}
				} catch (IOException ignored) {
					// the process went away; whatever we captured is enough
				}
			}, "life-essentials-decoder-stderr");
			thread.setDaemon(true);
			thread.start();
		}

		/**
		 * The most useful line the pipeline produced.
		 *
		 * <p>In a yt-dlp | ffmpeg pipeline the upstream failure is the real cause
		 * and the downstream one is just noise: yt-dlp getting a 403 makes ffmpeg
		 * report "Invalid data found", which sends you looking in the wrong place
		 * entirely. So an explicit ERROR line always wins over the last line.
		 */
		@Override
		public String diagnostics() {
			synchronized (errorTail) {
				String text = errorTail.toString().strip();
				if (text.isEmpty()) return "";
				String fallback = "";
				for (String line : text.split("\n")) {
					String trimmed = line.strip();
					if (trimmed.isEmpty()) continue;
					fallback = trimmed;
					int marker = trimmed.indexOf("ERROR:");
					if (marker >= 0) {
						return trimmed.substring(marker + "ERROR:".length()).strip();
					}
				}
				return fallback;
			}
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			return stream.read(buffer, offset, length);
		}

		@Override
		public void close() {
			try {
				stream.close();
			} catch (IOException ignored) {
				// closing a dying pipe is expected
			}
			for (Process process : processes) {
				process.destroyForcibly();
			}
		}
	}
}
