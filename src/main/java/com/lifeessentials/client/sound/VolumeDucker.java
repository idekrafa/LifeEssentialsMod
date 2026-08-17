package com.lifeessentials.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * Ducks in-game audio while phone music plays. Applied as a live multiplier in
 * {@code SoundEngineMixin} — the player's saved volume options are never
 * touched, so a crash can't leave their settings broken.
 */
public final class VolumeDucker {
	private static volatile float generalFactor = 1.0f;
	private static volatile float musicFactor = 1.0f;

	private VolumeDucker() {
	}

	public static void setFactors(float general, float music) {
		boolean changed = Math.abs(general - generalFactor) > 0.01f
				|| Math.abs(music - musicFactor) > 0.01f;
		generalFactor = general;
		musicFactor = music;
		if (changed) {
			refreshPlayingSounds();
		}
	}

	/** Called by SoundEngineMixin for every sound volume computation. */
	public static float factorFor(SoundSource source) {
		return switch (source) {
			case MASTER, VOICE -> 1.0f;
			case MUSIC, RECORDS -> musicFactor;
			default -> generalFactor;
		};
	}

	public static void reset() {
		setFactors(1.0f, 1.0f);
	}

	private static void refreshPlayingSounds() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) return;
		minecraft.execute(() -> {
			// any non-master category makes the sound engine re-evaluate all
			// currently playing sounds through calculateVolume (our hook)
			minecraft.getSoundManager().updateSourceVolume(SoundSource.AMBIENT,
					minecraft.options.getSoundSourceVolume(SoundSource.AMBIENT));
		});
	}
}
