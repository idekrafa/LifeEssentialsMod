package com.lifeessentials.client;

import com.lifeessentials.client.gui.SpeakerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Client-only entry point; only ever called from client-side branches. */
public final class SpeakerClientOpener {
	private SpeakerClientOpener() {
	}

	public static void open(BlockPos pos) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> minecraft.setScreen(new SpeakerScreen(pos)));
	}
}
