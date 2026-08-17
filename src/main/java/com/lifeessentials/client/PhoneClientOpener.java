package com.lifeessentials.client;

import com.lifeessentials.client.gui.HomeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** Client-only entry point; only ever called from client-side branches. */
public final class PhoneClientOpener {
	private PhoneClientOpener() {
	}

	public static void openHome(InteractionHand hand) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> minecraft.setScreen(new HomeScreen(hand)));
	}
}
