package com.lifeessentials.client.gui;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.client.ClientPhoneData;
import com.lifeessentials.client.music.MediaController;
import com.lifeessentials.client.music.MusicClientState;
import com.lifeessentials.client.music.NowPlaying;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

public class MusicAppScreen extends PhoneUiScreen {
	private static final ResourceLocation ICON_SPEAKER =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/gui/icon_speaker.png");
	private static final ResourceLocation ICON_AIRPODS =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/gui/icon_airpods.png");

	private Button spotifyTab;
	private Button musicTab;
	private Button playButton;
	private Button outputButton;
	private Button listenAlongButton;
	private Button openAppButton;

	public MusicAppScreen(InteractionHand hand) {
		super(Component.literal("Music"), hand);
	}

	private MediaController controller() {
		return MusicClientState.controller();
	}

	@Override
	protected void init() {
		super.init();
		MusicClientState.sessionActive = true;
		int cx = screenX + screenW / 2;

		spotifyTab = addRenderableWidget(Button.builder(Component.literal("Spotify"),
						b -> switchService("spotify"))
				.bounds(screenX + 3, screenY + 3, screenW / 2 - 5, 16).build());
		musicTab = addRenderableWidget(Button.builder(Component.literal("Music"),
						b -> switchService("apple_music"))
				.bounds(cx + 2, screenY + 3, screenW / 2 - 5, 16).build());

		addRenderableWidget(Button.builder(Component.literal("<<"), b -> {
			controller().previous();
			playClick();
		}).bounds(cx - 48, screenY + 62, 28, 20).build());
		playButton = addRenderableWidget(Button.builder(Component.literal("Play"), b -> {
			controller().playPause();
			playClick();
			updateWidgets();
		}).bounds(cx - 17, screenY + 62, 34, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">>"), b -> {
			controller().next();
			playClick();
		}).bounds(cx + 20, screenY + 62, 28, 20).build());

		addRenderableWidget(new VolumeSlider(screenX + 4, screenY + 96, screenW - 8, 16));

		outputButton = addRenderableWidget(Button.builder(Component.literal(""), b -> toggleOutput())
				.bounds(screenX + 4, screenY + 118, screenW - 8, 16).build());
		listenAlongButton = addRenderableWidget(Button.builder(Component.literal(""), b -> {
			MusicClientState.listenAlong = !MusicClientState.listenAlong;
			playClick();
			updateWidgets();
		}).bounds(screenX + 4, screenY + 138, screenW - 8, 16).build());

		openAppButton = addRenderableWidget(Button.builder(Component.literal("Open app"), b -> {
			controller().openApp();
			playClick();
		}).bounds(screenX + 4, screenY + 162, screenW - 8, 16).build());

		updateWidgets();
		controller().refreshStatus(this::updateWidgets);
	}

	private void switchService(String service) {
		MusicClientState.selectedService = service;
		playClick();
		updateWidgets();
		controller().refreshStatus(this::updateWidgets);
	}

	private void toggleOutput() {
		if (this.minecraft == null || this.minecraft.player == null) return;
		if (MusicClientState.speakerOn && !ClientPhoneData.isWearing(this.minecraft.player.getUUID())) {
			this.minecraft.player.displayClientMessage(Component.literal(
							"Put in your AirPods first (right-click them in your hand)")
					.withStyle(ChatFormatting.YELLOW), true);
			return;
		}
		playClick();
		MusicClientState.toggleSpeaker(this.minecraft);
		updateWidgets();
	}

	private void updateWidgets() {
		MediaController controller = controller();
		NowPlaying np = controller.nowPlaying();
		boolean spotify = "spotify".equals(MusicClientState.selectedService);
		spotifyTab.active = !spotify;
		musicTab.active = spotify;
		playButton.setMessage(Component.literal(np.playing() ? "Pause" : "Play"));
		outputButton.setMessage(Component.literal(MusicClientState.speakerOn
				? "Output: Speaker" : "Output: AirPods"));
		listenAlongButton.setMessage(Component.literal(MusicClientState.listenAlong
				? "Listen Along: On" : "Listen Along: Off"));
		openAppButton.visible = controller.isInstalled() && !controller.isRunning();
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		MediaController controller = controller();
		NowPlaying np = controller.nowPlaying();
		int cx = screenX + screenW / 2;

		String title;
		String subtitle;
		if (!controller.isInstalled()) {
			title = controller.displayName() + " not found";
			subtitle = "Install it on this computer";
		} else if (!controller.isRunning()) {
			title = controller.displayName() + " is closed";
			subtitle = "Tap Open app below";
		} else if (np.isEmpty()) {
			title = "Nothing playing";
			subtitle = "Start a song in " + controller.displayName();
		} else {
			title = np.title();
			subtitle = np.artist();
		}
		graphics.drawCenteredString(this.font, Component.literal(trim(title, screenW - 10)),
				cx, screenY + 28, COL_WHITE);
		graphics.drawCenteredString(this.font, Component.literal(trim(subtitle, screenW - 10)),
				cx, screenY + 40, COL_MUTED);
		if (!np.isEmpty() && np.duration() > 0) {
			graphics.drawCenteredString(this.font,
					Component.literal(clock(np.position()) + " / " + clock(np.duration())),
					cx, screenY + 51, COL_MUTED);
		}

		ResourceLocation outIcon = MusicClientState.speakerOn ? ICON_SPEAKER : ICON_AIRPODS;
		graphics.blit(outIcon, screenX + 6, screenY + 178, 0.0F, 0.0F, 20, 20, 20, 20);
		String note = MusicClientState.speakerOn
				? "Nearby players hear your music"
				: "Private listening";
		graphics.drawString(this.font, trim(note, screenW - 34),
				screenX + 30, screenY + 184, COL_MUTED, false);
	}

	private String trim(String text, int width) {
		if (this.font.width(text) <= width) return text;
		return this.font.plainSubstrByWidth(text, width - 6) + "…";
	}

	private static String clock(double seconds) {
		int total = (int) seconds;
		return (total / 60) + ":" + String.format("%02d", total % 60);
	}

	private static class VolumeSlider extends AbstractSliderButton {
		VolumeSlider(int x, int y, int width, int height) {
			super(x, y, width, height,
					Component.literal("Volume: " + MusicClientState.volume + "%"),
					MusicClientState.volume / 100.0);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Volume: " + (int) Math.round(this.value * 100) + "%"));
		}

		@Override
		protected void applyValue() {
			MusicClientState.setVolume((int) Math.round(this.value * 100));
		}
	}
}
