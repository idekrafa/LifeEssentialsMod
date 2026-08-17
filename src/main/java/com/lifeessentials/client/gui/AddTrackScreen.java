package com.lifeessentials.client.gui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.audio.MediaTools;
import com.lifeessentials.client.audio.MusicFolder;
import com.lifeessentials.client.audio.TrackImporter;
import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackUris;
import com.lifeessentials.net.MusicPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

/** Import screen: paste a link, or tap a file out of your music folder. */
public class AddTrackScreen extends PhoneUiScreen {
	private static final int ROW_HEIGHT = 14;

	private final String playlistId;

	private EditBox input;
	private String status = "";
	private boolean busy = false;
	private int scroll = 0;
	private volatile List<String> localFiles = List.of();

	public AddTrackScreen(InteractionHand hand, String playlistId) {
		super(Component.literal("Add music"), hand);
		this.playlistId = playlistId;
	}

	@Override
	protected void init() {
		super.init();
		input = new EditBox(this.font, screenX + 4, screenY + Y_INPUT, screenW - 8, 14,
				Component.literal("Link"));
		input.setMaxLength(TrackUris.MAX_URI + Track.MAX_TEXT);
		input.setHint(Component.literal("Link or song.mp3"));
		addRenderableWidget(input);
		addRenderableWidget(Button.builder(Component.literal("Add"), b -> submit())
				.bounds(screenX + 4, screenY + Y_ADD, screenW - 8, 15).build());
		setInitialFocus(input);

		// picks up an ffmpeg/yt-dlp install made while the game was running
		MediaTools.refreshAsync();
		CompletableFuture.supplyAsync(MusicFolder::list).thenAccept(files -> localFiles = files);
	}

	private void submit() {
		if (busy || input == null) return;
		String value = input.getValue();
		busy = true;
		status = "Reading…";
		playClick();
		TrackImporter.importAsync(value).thenAccept(result -> {
			if (this.minecraft == null) return;
			this.minecraft.execute(() -> {
				busy = false;
				status = result.message();
				if (!result.isEmpty()) {
					ClientNet.send(new MusicPayloads.LibraryAddTracksC2S(playlistId, result.tracks()));
					if (input != null) input.setValue("");
				}
			});
		});
	}

	private void addLocal(String fileName) {
		if (busy) return;
		if (input != null) input.setValue(fileName);
		submit();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
				&& input != null && input.isFocused()) {
			submit();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// One band per row of text, so nothing can creep over anything else.
	// The phone's screen is only 134 x 212, which is unforgiving.
	private static final int Y_TITLE = 2;
	private static final int Y_INPUT = 15;
	private static final int Y_ADD = 32;
	private static final int Y_STATUS = 52;
	private static final int Y_DECODERS = 63;
	private static final int Y_HEADER_BAND = 77;
	private static final int Y_HEADER_TEXT = 79;
	private static final int Y_LIST = 90;

	private int listTop() {
		return screenY + Y_LIST;
	}

	private int listBottom() {
		return screenY + screenH - 3;
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawCenteredString(this.font, Component.literal("Add music"),
				screenX + screenW / 2, screenY + Y_TITLE, COL_WHITE);

		if (!status.isEmpty()) {
			int colour = busy ? COL_MUTED : (status.startsWith("Added") ? 0xFF6FE08A : 0xFFFFC46B);
			graphics.drawString(this.font, trim(status, screenW - 8),
					screenX + 4, screenY + Y_STATUS, colour, false);
		} else {
			graphics.drawString(this.font, trim("Paste a link, or tap a file", screenW - 8),
					screenX + 4, screenY + Y_STATUS, COL_MUTED, false);
		}
		graphics.drawString(this.font, trim(MediaTools.status(), screenW - 8),
				screenX + 4, screenY + Y_DECODERS, COL_MUTED, false);

		graphics.fill(screenX + 2, screenY + Y_HEADER_BAND, screenX + screenW - 2,
				screenY + Y_HEADER_BAND + 11, 0x44000000);
		graphics.drawString(this.font, trim("Local files (" + localFiles.size() + ")", screenW - 10),
				screenX + 5, screenY + Y_HEADER_TEXT, COL_WHITE, false);

		List<String> files = localFiles;
		if (files.isEmpty()) {
			graphics.drawString(this.font, "Drop audio files in",
					screenX + 5, listTop() + 4, COL_MUTED, false);
			graphics.drawString(this.font, trim(MusicFolder.FOLDER_NAME, screenW - 10),
					screenX + 5, listTop() + 15, COL_MUTED, false);
			return;
		}

		graphics.enableScissor(screenX, listTop(), screenX + screenW, listBottom());
		int y = listTop() - scroll;
		for (String file : files) {
			if (y + ROW_HEIGHT >= listTop() && y <= listBottom()) {
				boolean hover = mouseY >= y && mouseY < y + ROW_HEIGHT - 1
						&& mouseY >= listTop() && mouseY <= listBottom()
						&& mouseX >= screenX + 2 && mouseX <= screenX + screenW - 2;
				if (hover) {
					graphics.fill(screenX + 2, y, screenX + screenW - 2, y + ROW_HEIGHT - 1, 0x55FFFFFF);
				}
				graphics.drawString(this.font, trim(file, screenW - 12),
						screenX + 6, y + 2, COL_WHITE, false);
			}
			y += ROW_HEIGHT;
		}
		graphics.disableScissor();
	}

	private String trim(String text, int width) {
		if (this.font.width(text) <= width) return text;
		return this.font.plainSubstrByWidth(text, Math.max(6, width - 6)) + "…";
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && mouseY >= listTop() && mouseY <= listBottom()
				&& mouseX >= screenX && mouseX <= screenX + screenW) {
			int index = (int) ((mouseY - listTop() + scroll) / ROW_HEIGHT);
			List<String> files = localFiles;
			if (index >= 0 && index < files.size()) {
				addLocal(files.get(index));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int content = localFiles.size() * ROW_HEIGHT;
		int maxScroll = Math.max(0, content - (listBottom() - listTop()));
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 10)));
		return true;
	}

	@Override
	protected void goBack() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new PlaylistDetailScreen(hand, playlistId));
		}
	}
}
