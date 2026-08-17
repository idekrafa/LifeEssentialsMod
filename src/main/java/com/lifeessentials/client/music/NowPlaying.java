package com.lifeessentials.client.music;

public record NowPlaying(String service, String trackId, String title, String artist,
		boolean playing, double position, double duration, int volume) {
	public static final NowPlaying NONE = new NowPlaying("", "", "", "", false, 0, 0, 0);

	public boolean isEmpty() {
		return title.isEmpty() && trackId.isEmpty();
	}
}
