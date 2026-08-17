package com.lifeessentials.client.music;

public interface MediaController {
	String serviceId();

	String displayName();

	boolean isInstalled();

	/** Shown under "<app> not found" in the music app. */
	default String unavailableReason() {
		return "Install it on this computer";
	}

	/** False when the backend can't set the app's own volume (slider still ducks the game). */
	default boolean supportsAppVolume() {
		return true;
	}

	/** Cached — updated by {@link #refreshStatus}. */
	boolean isRunning();

	/** Cached — updated by {@link #refreshStatus}. */
	NowPlaying nowPlaying();

	/** Refreshes {@link #isRunning()} and {@link #nowPlaying()} asynchronously. */
	void refreshStatus(Runnable onUpdated);

	void playPause();

	void next();

	void previous();

	/** 0..100 */
	void setVolume(int volume);

	void openApp();

	default void playTrack(String trackId) {
	}
}
