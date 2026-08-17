package com.lifeessentials.client.music;

import java.nio.file.Files;
import java.nio.file.Path;

public class SpotifyController extends MacMediaController {
	@Override
	public String serviceId() {
		return "spotify";
	}

	@Override
	public String displayName() {
		return "Spotify";
	}

	@Override
	protected String appName() {
		return "Spotify";
	}

	@Override
	protected String processName() {
		return "Spotify";
	}

	@Override
	public boolean isInstalled() {
		return AppleScriptRunner.isMac()
				&& (Files.exists(Path.of("/Applications/Spotify.app"))
				|| Files.exists(Path.of(System.getProperty("user.home", "/"), "Applications", "Spotify.app")));
	}

	@Override
	protected String stateScript() {
		return """
				tell application "Spotify"
					try
						if player state is stopped then return "stopped"
						set t to current track
						return (player state as string) & "|~|" & (name of t) & "|~|" & (artist of t) & "|~|" & (id of t) & "|~|" & (sound volume as string) & "|~|" & (player position as string) & "|~|" & (((duration of t) / 1000) as string)
					on error
						return "stopped"
					end try
				end tell""";
	}

	@Override
	protected NowPlaying parseState(String out) {
		String[] parts = out.split("\\|~\\|");
		if (parts.length < 7) return NowPlaying.NONE;
		boolean playing = parts[0].trim().equalsIgnoreCase("playing");
		return new NowPlaying(serviceId(), parts[3].trim(), parts[1].trim(), parts[2].trim(),
				playing, parseNumber(parts[5]), parseNumber(parts[6]), (int) parseNumber(parts[4]));
	}

	static double parseNumber(String s) {
		try {
			return Double.parseDouble(s.trim().replace(',', '.'));
		} catch (Exception e) {
			return 0;
		}
	}

	@Override
	public void playTrack(String trackId) {
		if (trackId == null || trackId.isEmpty()) return;
		tell("play track \"" + trackId.replace("\"", "") + "\"");
		refreshSoon(600);
	}
}
