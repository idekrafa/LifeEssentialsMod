package com.lifeessentials.client;

import com.lifeessentials.net.MusicPayloads;

/** Bodies of the music/speaker S2C handlers — never touched by a dedicated server. */
public final class ClientMusicPayloadHandler {
	private ClientMusicPayloadHandler() {
	}

	public static void handleLibrarySync(MusicPayloads.LibrarySyncS2C payload) {
		ClientMusicLibrary.load(payload.data());
	}

	public static void handleSpeakerSync(MusicPayloads.SpeakerSyncS2C payload) {
		ClientSpeakers.accept(payload);
	}
}
