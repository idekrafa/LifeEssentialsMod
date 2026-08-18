package com.lifeessentials.music;

/** Where the audio for a track actually comes from. */
public enum TrackSource {
	/** A file inside each player's own {@code lifeessentials-music} folder. */
	FILE("Local file"),
	/** A direct http(s) link to an audio file or stream. */
	URL("Link"),
	/** A YouTube video id — resolved to a stream by yt-dlp on each client. */
	YOUTUBE("YouTube"),
	/** A {@code spotify:track:…} uri — handed to each listener's desktop Spotify. */
	SPOTIFY("Spotify");

	private final String label;

	TrackSource(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public static TrackSource byName(String name) {
		for (TrackSource source : values()) {
			if (source.name().equalsIgnoreCase(name)) return source;
		}
		return URL;
	}
}
