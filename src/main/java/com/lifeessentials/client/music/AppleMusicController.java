package com.lifeessentials.client.music;

import java.nio.file.Files;
import java.nio.file.Path;

public class AppleMusicController extends MacMediaController {
	@Override
	public String serviceId() {
		return "apple_music";
	}

	@Override
	public String displayName() {
		return "Apple Music";
	}

	@Override
	protected String appName() {
		return "Music";
	}

	@Override
	protected String processName() {
		return "Music";
	}

	@Override
	public boolean isInstalled() {
		return AppleScriptRunner.isMac()
				&& (Files.exists(Path.of("/System/Applications/Music.app"))
				|| Files.exists(Path.of("/Applications/Music.app")));
	}

	@Override
	protected String stateScript() {
		return """
				tell application "Music"
					try
						if player state is stopped then return "stopped"
						set t to current track
						return (player state as string) & "|~|" & (name of t) & "|~|" & (artist of t) & "|~|" & (sound volume as string) & "|~|" & (player position as string) & "|~|" & ((duration of t) as string)
					on error
						return "stopped"
					end try
				end tell""";
	}

	@Override
	protected NowPlaying parseState(String out) {
		String[] parts = out.split("\\|~\\|");
		if (parts.length < 6) return NowPlaying.NONE;
		boolean playing = parts[0].trim().equalsIgnoreCase("playing");
		String title = parts[1].trim();
		String artist = parts[2].trim();
		// Apple Music has no share-able track id over AppleScript; synthesize one
		// for change detection (not usable for listen-along).
		String trackId = "am:" + title + "|" + artist;
		return new NowPlaying(serviceId(), trackId, title, artist, playing,
				SpotifyController.parseNumber(parts[4]), SpotifyController.parseNumber(parts[5]),
				(int) SpotifyController.parseNumber(parts[3]));
	}
}
