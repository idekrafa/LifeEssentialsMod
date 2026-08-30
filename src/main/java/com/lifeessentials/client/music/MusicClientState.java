package com.lifeessentials.client.music;

import java.util.Set;

import com.lifeessentials.ModItems;
import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.audio.AudioEngine;
import com.lifeessentials.client.gui.MusicAppScreen;
import com.lifeessentials.client.sound.VolumeDucker;
import com.lifeessentials.net.ModPayloads;
import net.minecraft.client.Minecraft;

/** Client-side music session: polling, ducking, and speaker broadcasting. */
public final class MusicClientState {
	public static final MediaController SPOTIFY = Controllers.spotify();
	public static final MediaController APPLE_MUSIC = Controllers.appleMusic();

	public static String selectedService = "spotify";
	/** Phone volume slider, 0..100. Higher music volume = quieter game. */
	public static int volume = 60;
	/** true = speaker (nearby players notified), false = private (AirPods). */
	public static boolean speakerOn = true;
	public static boolean listenAlong = true;
	/** Once music has been touched through the phone we keep polling in the background. */
	public static boolean sessionActive = false;

	private static int tickCounter = 0;
	private static String lastSpeakerKey = "";
	private static long lastSpeakerSentAt = 0;
	private static String lastListenAlongTrack = "";

	private MusicClientState() {
	}

	public static MediaController controller() {
		return "apple_music".equals(selectedService) ? APPLE_MUSIC : SPOTIFY;
	}

	public static void clientTick(Minecraft minecraft) {
		if (minecraft.player == null) return;
		if (minecraft.screen instanceof MusicAppScreen) {
			sessionActive = true;
		}
		tickCounter++;
		if (sessionActive && tickCounter % 40 == 0) {
			controller().refreshStatus(() -> {
				applyDucking();
				maybeBroadcast(minecraft);
			});
		}
		applyDucking();
	}

	private static void maybeBroadcast(Minecraft minecraft) {
		if (minecraft.player == null || !ClientNet.canSend(ModPayloads.SpeakerUpdateC2S.TYPE)) {
			return;
		}
		NowPlaying np = controller().nowPlaying();
		boolean holdsPhone = minecraft.player.getInventory().hasAnyOf(Set.of(ModItems.PHONE.get()));
		boolean broadcasting = speakerOn && sessionActive && np.playing() && holdsPhone;
		String key = broadcasting
				? controller().serviceId() + "|" + np.trackId() + "|" + np.title()
				: "off";
		long now = System.currentTimeMillis();
		if (key.equals(lastSpeakerKey) && (key.equals("off") || now - lastSpeakerSentAt < 6000)) {
			return; // nothing new; refresh the server every ~6s while broadcasting
		}
		lastSpeakerKey = key;
		lastSpeakerSentAt = now;
		ClientNet.send(new ModPayloads.SpeakerUpdateC2S(broadcasting,
				controller().serviceId(), np.trackId(), np.title(), np.artist(), np.playing()));
	}

	private static void applyDucking() {
		NowPlaying np = controller().nowPlaying();
		float loudness = 0.0f;
		if (sessionActive && np.playing()) {
			loudness = volume / 100.0f;
		}
		// a JBL speaker playing next to you ducks the game just like the phone does
		loudness = Math.max(loudness, AudioEngine.audibleGain());

		float general = 1.0f;
		float music = 1.0f;
		if (loudness > 0.01f) {
			general = Math.max(0.12f, 1.0f - 0.85f * loudness);
			music = Math.min(general, 0.3f); // game music always ducks hard under real music
		}
		VolumeDucker.setFactors(general, music);
	}

	public static void setVolume(int newVolume) {
		volume = Math.max(0, Math.min(100, newVolume));
		controller().setVolume(volume);
		applyDucking();
	}

	public static void toggleSpeaker(Minecraft minecraft) {
		speakerOn = !speakerOn;
		lastSpeakerKey = "";
		lastSpeakerSentAt = 0;
		maybeBroadcast(minecraft);
	}

	/** A nearby speaker is playing this Spotify track — play it locally too. */
	public static void handleListenAlong(String trackId) {
		if (!listenAlong || trackId == null || trackId.isEmpty()) return;
		if (!trackId.startsWith("spotify:")) return; // SMTC ids can't be played remotely
		if (!SPOTIFY.isInstalled() && !SPOTIFY.isRunning()) return;
		if (trackId.equals(lastListenAlongTrack) || trackId.equals(SPOTIFY.nowPlaying().trackId())) {
			return;
		}
		lastListenAlongTrack = trackId;
		sessionActive = true;
		selectedService = "spotify";
		SPOTIFY.playTrack(trackId);
	}

	public static void onDisconnect() {
		sessionActive = false;
		speakerOn = true;
		tickCounter = 0;
		lastSpeakerKey = "";
		lastSpeakerSentAt = 0;
		lastListenAlongTrack = "";
		VolumeDucker.reset();
	}
}
