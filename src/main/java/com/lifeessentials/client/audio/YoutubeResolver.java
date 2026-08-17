package com.lifeessentials.client.audio;

import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackSource;
import com.lifeessentials.music.TrackUris;

/** Thin wrapper around {@code yt-dlp} for importing and for playback. */
public final class YoutubeResolver {
	/**
	 * WebM/Opus first, and the order matters.
	 *
	 * <p>Playback pipes yt-dlp straight into ffmpeg, and an .m4a is an MP4 whose
	 * index can sit at the end of the file — down a pipe there is no seeking back
	 * to it, so ffmpeg intermittently rejects the stream outright. WebM is a
	 * streaming container and decodes from the first byte. Its Opus audio is
	 * 48 kHz too, which is exactly what the mixer wants, so nothing is resampled.
	 */
	public static final String AUDIO_FORMAT =
			"bestaudio[ext=webm]/bestaudio[acodec=opus]/bestaudio/best";
	private static final long METADATA_TIMEOUT_SECONDS = 45;
	private static final String PRINT = "%(id)s\t%(title)s\t%(uploader)s\t%(duration)s";

	private YoutubeResolver() {
	}

	/**
	 * Reads title/uploader/length for a single video, or every entry of a
	 * playlist link. Runs on the importing player's machine, so the server never
	 * needs yt-dlp itself.
	 */
	public static List<Track> resolveForImport(String url, int limit) {
		String tool = MediaTools.ytDlp();
		if (tool == null) return List.of();

		List<String> command = new ArrayList<>(List.of(tool, "--ignore-errors", "--no-warnings",
				"--skip-download", "--print", PRINT));
		if (TrackUris.isYoutubePlaylist(url)) {
			command.add("--flat-playlist");
			command.add("--playlist-items");
			command.add("1-" + Math.max(1, limit));
		} else {
			command.add("--no-playlist");
		}
		command.add(url);

		String output = MediaTools.capture(command, METADATA_TIMEOUT_SECONDS);
		if (output == null) return List.of();

		List<Track> tracks = new ArrayList<>();
		for (String line : output.split("\r?\n")) {
			if (line.isBlank()) continue;
			String[] parts = line.split("\t", -1);
			if (parts.length < 1) continue;
			String id = parts[0].strip();
			if (id.isEmpty() || "NA".equals(id)) continue;
			String title = parts.length > 1 ? clean(parts[1]) : id;
			String uploader = parts.length > 2 ? clean(parts[2]) : "YouTube";
			int duration = parts.length > 3 ? parseSeconds(parts[3]) : 0;
			tracks.add(new Track("", title, uploader, TrackSource.YOUTUBE, id, duration));
			if (tracks.size() >= limit) break;
		}
		return tracks;
	}

	private static String clean(String value) {
		String cleaned = value.strip();
		return cleaned.isEmpty() || "NA".equals(cleaned) ? "" : cleaned;
	}

	private static int parseSeconds(String value) {
		try {
			return (int) Math.round(Double.parseDouble(value.strip()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
