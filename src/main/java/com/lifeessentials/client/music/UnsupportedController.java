package com.lifeessentials.client.music;

/** Placeholder for operating systems without a media backend yet (e.g. Linux). */
public class UnsupportedController implements MediaController {
	private final String serviceId;
	private final String displayName;

	public UnsupportedController(String serviceId, String displayName) {
		this.serviceId = serviceId;
		this.displayName = displayName;
	}

	@Override
	public String serviceId() {
		return serviceId;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	@Override
	public boolean isInstalled() {
		return false;
	}

	@Override
	public String unavailableReason() {
		return "Music control isn't supported on this OS yet";
	}

	@Override
	public boolean isRunning() {
		return false;
	}

	@Override
	public NowPlaying nowPlaying() {
		return NowPlaying.NONE;
	}

	@Override
	public void refreshStatus(Runnable onUpdated) {
		if (onUpdated != null) onUpdated.run();
	}

	@Override
	public void playPause() {
	}

	@Override
	public void next() {
	}

	@Override
	public void previous() {
	}

	@Override
	public void setVolume(int volume) {
	}

	@Override
	public void openApp() {
	}
}
