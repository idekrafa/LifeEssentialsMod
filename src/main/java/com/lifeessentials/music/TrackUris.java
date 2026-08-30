package com.lifeessentials.music;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parsing and validation for everything a player can paste into the phone.
 *
 * <p>The server runs {@link #sanitize} over every imported track before it
 * reaches the library, so a hand-crafted packet can't talk other people's
 * clients into opening a local file or a non-http protocol.
 */
public final class TrackUris {
	private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{6,24}");
	private static final Pattern SPOTIFY_TRACK = Pattern.compile("[A-Za-z0-9]{16,32}");
	private static final Pattern UNSAFE_PATH = Pattern.compile("(^[/\\\\])|(^[A-Za-z]:)|(\\.\\.)|([\\\\])");

	/** Keeps a hand-crafted packet from bloating the library sync. */
	public static final int MAX_URI = 300;

	public static final String[] AUDIO_EXTENSIONS =
			{".mp3", ".wav", ".ogg", ".oga", ".opus", ".flac", ".m4a", ".aac", ".aiff", ".aif", ".wma"};

	private TrackUris() {
	}

	/** Returns a cleaned copy, or {@code null} when the track must be rejected. */
	public static Track sanitize(Track track) {
		if (track == null || track.uri() == null || track.uri().isBlank()) return null;
		String uri = track.uri().strip();
		if (uri.length() > MAX_URI) return null;
		switch (track.source()) {
			case FILE -> {
				if (!isSafeRelativePath(uri)) return null;
			}
			case URL -> {
				if (!isHttpUrl(uri)) return null;
			}
			case YOUTUBE -> {
				if (!YOUTUBE_ID.matcher(uri).matches()) return null;
			}
			case SPOTIFY -> {
				String id = spotifyTrackId(uri);
				if (id == null) return null;
				uri = "spotify:track:" + id;
			}
		}
		return new Track(track.id(), stripControls(track.title()), stripControls(track.artist()),
				track.source(), uri, track.durationSeconds());
	}

	private static String stripControls(String text) {
		if (text == null) return "";
		StringBuilder sb = new StringBuilder(text.length());
		for (char c : text.toCharArray()) {
			sb.append(c < 0x20 || c == 0x7F ? ' ' : c);
		}
		return sb.toString().strip();
	}

	/** A file name inside the music folder: relative, forward slashes, no escaping. */
	public static boolean isSafeRelativePath(String path) {
		return !path.isBlank() && !UNSAFE_PATH.matcher(path).find() && hasAudioExtension(path);
	}

	public static boolean hasAudioExtension(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (String extension : AUDIO_EXTENSIONS) {
			if (lower.endsWith(extension)) return true;
		}
		return false;
	}

	public static boolean isHttpUrl(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
		try {
			return java.net.URI.create(url).getHost() != null;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	// -------------------------------------------------------------- youtube

	/**
	 * Video id from any of the usual YouTube link shapes — including
	 * music.youtube.com, m.youtube.com, shorts, embeds and youtu.be — or
	 * {@code null} when there isn't one.
	 */
	public static String youtubeVideoId(String input) {
		String url = input.strip();
		String lower = url.toLowerCase(Locale.ROOT);
		String id = null;
		if (lower.contains("youtu.be/")) {
			id = after(url, "youtu.be/");
		} else if (lower.contains("/shorts/")) {
			id = after(url, "/shorts/");
		} else if (lower.contains("/embed/")) {
			id = after(url, "/embed/");
		} else if (lower.contains("/live/")) {
			id = after(url, "/live/");
		} else {
			// only a real query parameter counts, so "…?app=desktop&v=ID" works and
			// a stray "v=" inside some other value doesn't
			int at = indexOfParam(lower, "v");
			if (at >= 0) id = url.substring(at);
		}
		if (id == null) return null;
		id = cutAt(id, "&", "?", "/", "#");
		return YOUTUBE_ID.matcher(id).matches() ? id : null;
	}

	/** Start offset of a query parameter's value, or -1. */
	private static int indexOfParam(String lowerUrl, String name) {
		for (String prefix : new String[] { "?" + name + "=", "&" + name + "=" }) {
			int at = lowerUrl.indexOf(prefix);
			if (at >= 0) return at + prefix.length();
		}
		return -1;
	}

	/**
	 * True only for links that really mean "the whole playlist". Sharing a song
	 * from YouTube Music tacks a radio {@code &list=RDAMVM…} onto the url, and
	 * importing a hundred-track radio queue is not what that person wanted — so
	 * a link that also names a video counts as just that video.
	 */
	public static boolean isYoutubePlaylist(String input) {
		String lower = input.toLowerCase(Locale.ROOT);
		if (!isYoutubeLink(input) || indexOfParam(lower, "list") < 0) return false;
		return lower.contains("/playlist") || indexOfParam(lower, "v") < 0;
	}

	/** Any YouTube host, including YouTube Music and the mobile site. */
	public static boolean isYoutubeLink(String input) {
		String lower = input.toLowerCase(Locale.ROOT);
		return lower.contains("youtube.com/") || lower.contains("youtu.be/")
				|| lower.contains("youtube-nocookie.com/");
	}

	// -------------------------------------------------------------- spotify

	/** Track id from {@code spotify:track:…} or an open.spotify.com link. */
	public static String spotifyTrackId(String input) {
		String value = input.strip();
		String id = null;
		if (value.startsWith("spotify:track:")) {
			id = value.substring("spotify:track:".length());
		} else if (value.toLowerCase(Locale.ROOT).contains("open.spotify.com/track/")) {
			id = after(value, "/track/");
		}
		if (id == null) return null;
		id = cutAt(id, "?", "&", "/", "#");
		return SPOTIFY_TRACK.matcher(id).matches() ? id : null;
	}

	public static boolean isSpotifyLink(String input) {
		String lower = input.toLowerCase(Locale.ROOT);
		return lower.startsWith("spotify:") || lower.contains("open.spotify.com/");
	}

	// --------------------------------------------------------------- helpers

	private static String after(String text, String marker) {
		int index = text.indexOf(marker);
		return index < 0 ? null : text.substring(index + marker.length());
	}

	private static String cutAt(String text, String... markers) {
		int cut = text.length();
		for (String marker : markers) {
			int index = text.indexOf(marker);
			if (index >= 0) cut = Math.min(cut, index);
		}
		return text.substring(0, cut);
	}

	/** A readable name for a file or link when no real metadata is available. */
	public static String prettyName(String uri) {
		String name = uri;
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (slash >= 0 && slash < name.length() - 1) {
			name = name.substring(slash + 1);
		}
		int query = name.indexOf('?');
		if (query > 0) name = name.substring(0, query);
		int dot = name.lastIndexOf('.');
		if (dot > 0 && hasAudioExtension(name)) name = name.substring(0, dot);
		name = name.replace('_', ' ').strip();
		return name.isEmpty() ? uri : name;
	}
}
