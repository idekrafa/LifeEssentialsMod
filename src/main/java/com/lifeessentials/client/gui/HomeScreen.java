package com.lifeessentials.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.client.ClientPhoneData;
import com.lifeessentials.client.music.MusicClientState;
import com.lifeessentials.phone.PhoneNumbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

public class HomeScreen extends PhoneUiScreen {
	private static final ResourceLocation ICON_SPOTIFY =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/gui/icon_spotify.png");
	private static final ResourceLocation ICON_APPLE_MUSIC =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/gui/icon_apple_music.png");
	private static final ResourceLocation ICON_MESSAGES =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/gui/icon_messages.png");
	private static final ResourceLocation ICON_PLAYLISTS =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/gui/icon_playlists.png");

	private record AppIcon(String label, ResourceLocation texture, Runnable open) {
	}

	private final List<AppIcon> apps = new ArrayList<>();

	public HomeScreen(InteractionHand hand) {
		super(Component.literal("iPhone"), hand);
	}

	@Override
	protected void init() {
		super.init();
		apps.clear();
		apps.add(new AppIcon("Spotify", ICON_SPOTIFY, () -> openMusic("spotify")));
		apps.add(new AppIcon("Music", ICON_APPLE_MUSIC, () -> openMusic("apple_music")));
		apps.add(new AppIcon("Messages", ICON_MESSAGES,
				() -> this.minecraft.setScreen(new MessagesAppScreen(hand))));
		apps.add(new AppIcon("Playlists", ICON_PLAYLISTS,
				() -> this.minecraft.setScreen(new PlaylistsAppScreen(hand))));
	}

	private void openMusic(String service) {
		MusicClientState.selectedService = service;
		this.minecraft.setScreen(new MusicAppScreen(hand));
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		String number = myNumber();
		String label = number == null ? "Activating…" : PhoneNumbers.pretty(number);
		graphics.drawCenteredString(this.font, Component.literal("My iPhone"),
				screenX + screenW / 2, screenY + 8, COL_WHITE);
		graphics.drawCenteredString(this.font, Component.literal(label),
				screenX + screenW / 2, screenY + 20, COL_MUTED);

		for (int i = 0; i < apps.size(); i++) {
			AppIcon app = apps.get(i);
			int x = iconX(i);
			int y = iconY(i);
			boolean hover = mouseX >= x - 4 && mouseX <= x + 24 && mouseY >= y - 3 && mouseY <= y + 31;
			if (hover) {
				graphics.fill(x - 4, y - 3, x + 24, y + 31, 0x33FFFFFF);
			}
			graphics.blit(app.texture(), x, y, 0.0F, 0.0F, 20, 20, 20, 20);
			if (app.texture() == ICON_MESSAGES && !ClientPhoneData.unread.isEmpty()) {
				graphics.fill(x + 16, y - 2, x + 22, y + 4, 0xFFE8352C); // unread badge
			}
			graphics.drawCenteredString(this.font, Component.literal(app.label()),
					x + 10, y + 23, COL_WHITE);
		}
	}

	private int iconX(int index) {
		int col = index % 3;
		return screenX + 12 + col * 40;
	}

	private int iconY(int index) {
		int row = index / 3;
		return screenY + 46 + row * 44;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			for (int i = 0; i < apps.size(); i++) {
				int x = iconX(i);
				int y = iconY(i);
				if (mouseX >= x - 4 && mouseX <= x + 24 && mouseY >= y - 3 && mouseY <= y + 31) {
					playClick();
					apps.get(i).open().run();
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void goBack() {
		// home screen: pressing home/ESC puts the phone away
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
		}
	}
}
