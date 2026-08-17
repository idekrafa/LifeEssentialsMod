package com.lifeessentials.client.audio;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackSource;
import com.lifeessentials.music.TrackUris;
import com.lifeessentials.net.MusicPayloads;

/**
 * Turns whatever a player typed into the phone into library tracks.
 *
 * <p>Everything happens on the importing client — yt-dlp lookups included — so
 * a vanilla-ish dedicated server never needs any of these tools installed.
 */
public final class TrackImporter {
	/** Optional "Nice Title | <link>" prefix. */
	private static final String TITLE_SEPARATOR = " | ";

	public record Result(List<Track> tracks, String message) {
		public boolean isEmpty() {
			return tracks.isEmpty();
		}

		static Result failed(String message) {
			return new Result(List.of(), message);
		}
	}

	private TrackImporter() {
	}

	public static CompletableFuture<Result> importAsync(String input) {
		String raw = input == null ? "" : input.strip();
		if (raw.isEmpty()) {
			return CompletableFuture.completedFuture(Result.failed("Type a link or a file name"));
		}
		return CompletableFuture.supplyAsync(() -> resolve(raw));
	}

	private static Result resolve(String raw) {
		String customTitle = "";
		String value = raw;
		int separator = raw.indexOf(TITLE_SEPARATOR);
		if (separator > 0) {
			customTitle = raw.substring(0, separator).strip();
			value = raw.substring(separator + TITLE_SEPARATOR.length()).strip();
		}

		if (TrackUris.isSpotifyLink(value)) {
			return spotify(value, customTitle);
		}
		if (TrackUris.isYoutubeLink(value)) {
			return youtube(value, customTitle);
		}
		if (TrackUris.isHttpUrl(value)) {
			// worth the round trip: a known duration lets the speaker advance the
			// queue on its own clock instead of waiting for a listener to report
			List<Track> resolved = LavaLookup.resolve(value, TrackSource.URL, 1);
			int duration = resolved.isEmpty() ? 0 : resolved.get(0).durationSeconds();
			String title = customTitle.isEmpty() ? TrackUris.prettyName(value) : customTitle;
			return new Result(List.of(new Track("", title, "Link", TrackSource.URL, value, duration)),
					"Added " + title);
		}
		return localFile(value, customTitle);
	}

	private static Result spotify(String value, String customTitle) {
		String id = TrackUris.spotifyTrackId(value);
		if (id == null) {
			return Result.failed("That doesn't look like a Spotify track link");
		}
		String title = customTitle.isEmpty() ? "Spotify track" : customTitle;
		return new Result(
				List.of(new Track("", title, "Spotify", TrackSource.SPOTIFY, "spotify:track:" + id, 0)),
				"Added a Spotify track — listeners hear it through their own Spotify app");
	}

	private static Result youtube(String value, String customTitle) {
		List<Track> resolved = LavaLookup.resolve(value, TrackSource.YOUTUBE, MusicPayloads.MAX_IMPORT);
		if (!resolved.isEmpty()) {
			if (resolved.size() == 1 && !customTitle.isEmpty()) {
				Track only = resolved.get(0);
				resolved = List.of(new Track("", customTitle, only.artist(), TrackSource.YOUTUBE,
						only.uri(), only.durationSeconds()));
			}
			return new Result(resolved, resolved.size() == 1
					? "Added " + resolved.get(0).displayTitle()
					: "Added " + resolved.size() + " tracks from the playlist");
		}
		// the lookup failed, but a bare id is still playable — store it rather than
		// making the player paste the link again
		String id = TrackUris.youtubeVideoId(value);
		if (id == null) {
			return Result.failed("That YouTube link couldn't be read");
		}
		String title = customTitle.isEmpty() ? "YouTube video" : customTitle;
		return new Result(List.of(new Track("", title, "YouTube", TrackSource.YOUTUBE, id, 0)),
				"Added the video (no metadata available)");
	}

	private static Result localFile(String value, String customTitle) {
		String name = matchLocalFile(value);
		if (name == null) {
			if (!TrackUris.hasAudioExtension(value)) {
				return Result.failed("Paste a link, or a file name like song.mp3");
			}
			return Result.failed("\"" + value + "\" isn't in your " + MusicFolder.FOLDER_NAME
					+ " folder");
		}
		Path path = MusicFolder.resolve(name);
		int duration = path == null ? 0
				: LavaLookup.durationSeconds(path.toAbsolutePath().toString(), TrackSource.FILE);
		String title = customTitle.isEmpty() ? TrackUris.prettyName(name) : customTitle;
		return new Result(
				List.of(new Track("", title, "Local file", TrackSource.FILE, name, duration)),
				"Added " + title);
	}

	/** Accepts the exact name or a case-insensitive match from the folder. */
	private static String matchLocalFile(String value) {
		String cleaned = value.replace('\\', '/').strip();
		if (MusicFolder.resolve(cleaned) != null) return cleaned;
		for (String candidate : MusicFolder.list()) {
			if (candidate.equalsIgnoreCase(cleaned)) return candidate;
		}
		return null;
	}
}
