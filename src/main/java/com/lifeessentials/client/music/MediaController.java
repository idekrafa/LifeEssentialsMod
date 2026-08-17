package com.lifeessentials.client.music;

public interface MediaController {
	String serviceId();

	String displayName();

	boolean isInstalled();

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
