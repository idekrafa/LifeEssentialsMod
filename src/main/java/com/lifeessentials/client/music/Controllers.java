package com.lifeessentials.client.music;

/** Picks the media backend for the current operating system. */
public final class Controllers {
	private Controllers() {
	}

	public static MediaController spotify() {
		if (AppleScriptRunner.isMac()) return new SpotifyController();
		if (PowerShellRunner.isWindows()) return WindowsSmtcController.spotify();
		return new UnsupportedController("spotify", "Spotify");
	}

	public static MediaController appleMusic() {
		if (AppleScriptRunner.isMac()) return new AppleMusicController();
		if (PowerShellRunner.isWindows()) return WindowsSmtcController.appleMusic();
		return new UnsupportedController("apple_music", "Apple Music");
	}
}
