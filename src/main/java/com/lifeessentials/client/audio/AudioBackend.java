package com.lifeessentials.client.audio;

import java.util.List;

/**
 * The decoder, seen from the mod side.
 *
 * <p>Implemented in the {@code backend} source set and loaded by
 * {@link BackendLoader} into a child classloader, because the engine cannot live in
 * this jar as classes — see the note in {@code build.gradle}.
 *
 * <p>Everything crossing this boundary is a JDK type or {@link PcmSource}, both of
 * which are loaded by the <em>parent</em>. That is what makes the two sides agree
 * on types: the backend can see these interfaces, and nothing of LavaPlayer's ever
 * has to be visible to the mod.
 */
public interface AudioBackend {
	/** Index into the arrays returned by {@link #resolve}. */
	int TITLE = 0;
	int AUTHOR = 1;
	int IDENTIFIER = 2;
	int DURATION_SECONDS = 3;

	/**
	 * Opens a decoded, seeked PCM stream in {@link PcmSource#FORMAT}.
	 *
	 * @param identifier a url, a YouTube watch link, or an absolute file path
	 */
	PcmSource open(String identifier, long startMs) throws BackendFailure;

	/**
	 * Metadata for the import screen: one {@code String[]} per track, indexed by the
	 * constants above. Returns an empty list on any failure — import degrades to a
	 * bare link rather than surfacing engine detail to the player.
	 *
	 * <p>A duration of {@code 0} means unknown, including for live streams, whose
	 * reported length is meaningless.
	 */
	List<String[]> resolve(String identifier, int limit);

	void shutdown();
}
