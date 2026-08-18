package com.lifeessentials.client.audio;

import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackSource;

/**
 * Metadata lookups for the import screen, translated out of the backend's
 * deliberately dumb {@code String[]} rows into library {@link Track}s.
 *
 * <p>Knowing a duration matters beyond the UI: when {@code Track#durationSeconds} is
 * set, the speaker's block entity advances the queue on its own clock instead of
 * waiting for a listener to report the end of the track.
 */
final class LavaLookup {
	private LavaLookup() {
	}

	/** Resolves one identifier into library tracks, or an empty list on any failure. */
	static List<Track> resolve(String identifier, TrackSource source, int limit) {
		AudioBackend backend = BackendLoader.backend();
		if (backend == null) return List.of();

		List<Track> tracks = new ArrayList<>();
		for (String[] row : backend.resolve(identifier, limit)) {
			// YouTube's identifier is the bare video id, which is what the library
			// stores and what the trust boundary on the server expects; every other
			// source keeps whatever the player actually pasted
			String uri = source == TrackSource.YOUTUBE
					? row[AudioBackend.IDENTIFIER]
					: identifier;
			tracks.add(new Track("", row[AudioBackend.TITLE], row[AudioBackend.AUTHOR],
					source, uri, seconds(row[AudioBackend.DURATION_SECONDS])));
		}
		return tracks;
	}

	/** Length of one identifier in seconds, or 0 when it can't be read. */
	static int durationSeconds(String identifier, TrackSource source) {
		List<Track> tracks = resolve(identifier, source, 1);
		return tracks.isEmpty() ? 0 : tracks.get(0).durationSeconds();
	}

	private static int seconds(String value) {
		try {
			return (int) Math.max(0, Long.parseLong(value));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
