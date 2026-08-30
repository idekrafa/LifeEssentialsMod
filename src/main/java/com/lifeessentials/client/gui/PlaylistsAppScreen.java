package com.lifeessentials.client.gui;

import java.util.List;

import com.lifeessentials.client.ClientMusicLibrary;
import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.ClientSpeakers;
import com.lifeessentials.music.Playlist;
import com.lifeessentials.net.MusicPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

/** The phone's playlist manager: every playlist on the server, in one list. */
public class PlaylistsAppScreen extends PhoneUiScreen {
	private static final int ROW_HEIGHT = 24;

	private boolean creating = false;
	private EditBox nameField;
	private int scroll = 0;
	private int refreshTimer = 0;

	public PlaylistsAppScreen(InteractionHand hand) {
		super(Component.literal("Playlists"), hand);
	}

	@Override
	protected void init() {
		super.init();
		if (creating) {
			nameField = new EditBox(this.font, screenX + 4, screenY + 15, screenW - 34, 14,
					Component.literal("Name"));
			nameField.setMaxLength(Playlist.MAX_NAME);
			nameField.setHint(Component.literal("Playlist name"));
			addRenderableWidget(nameField);
			addRenderableWidget(Button.builder(Component.literal("OK"), b -> confirmCreate())
					.bounds(screenX + screenW - 28, screenY + 15, 24, 14).build());
			setInitialFocus(nameField);
		} else {
			addRenderableWidget(Button.builder(Component.literal("+ New Playlist"), b -> {
				playClick();
				creating = true;
				rebuildWidgets();
			}).bounds(screenX + 4, screenY + 15, screenW - 8, 15).build());
		}
		ClientNet.send(MusicPayloads.LibraryRequestC2S.INSTANCE);
	}

	private void confirmCreate() {
		String name = nameField == null ? "" : nameField.getValue().strip();
		if (name.isEmpty()) return;
		ClientNet.send(new MusicPayloads.LibraryCommandC2S("create", "", name, 0));
		playClick();
		creating = false;
		rebuildWidgets();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (creating && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
			confirmCreate();
			return true;
		}
		if (creating && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			creating = false;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void tick() {
		super.tick();
		if (++refreshTimer % 100 == 0) {
			ClientNet.send(MusicPayloads.LibraryRequestC2S.INSTANCE);
		}
	}

	private int listTop() {
		return screenY + 34;
	}

	private int listBottom() {
		return screenY + screenH - 26;
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawCenteredString(this.font, Component.literal("Playlists"),
				screenX + screenW / 2, screenY + 3, COL_WHITE);

		List<Playlist> playlists = ClientMusicLibrary.playlists();
		if (playlists.isEmpty()) {
			String message = ClientMusicLibrary.isLoaded() ? "No playlists yet" : "Loading…";
			graphics.drawCenteredString(this.font, Component.literal(message),
					screenX + screenW / 2, listTop() + 16, COL_MUTED);
		} else {
			graphics.enableScissor(screenX, listTop(), screenX + screenW, listBottom());
			int y = listTop() - scroll;
			for (Playlist playlist : playlists) {
				if (y + ROW_HEIGHT >= listTop() && y <= listBottom()) {
					boolean hover = mouseY >= y && mouseY < y + ROW_HEIGHT - 2
							&& mouseY >= listTop() && mouseY <= listBottom()
							&& mouseX >= screenX + 2 && mouseX <= screenX + screenW - 2;
					graphics.fill(screenX + 2, y, screenX + screenW - 2, y + ROW_HEIGHT - 2,
							hover ? 0x55FFFFFF : 0x33000000);
					graphics.drawString(this.font, trim(playlist.name(), screenW - 14),
							screenX + 6, y + 3, COL_WHITE, false);
					String meta = playlist.size() + (playlist.size() == 1 ? " track" : " tracks");
					if (!playlist.ownerName().isEmpty()) meta += " · " + playlist.ownerName();
					graphics.drawString(this.font, trim(meta, screenW - 14),
							screenX + 6, y + 13, COL_MUTED, false);
				}
				y += ROW_HEIGHT;
			}
			graphics.disableScissor();
		}

		drawSpeakerFooter(graphics);
	}

	private void drawSpeakerFooter(GuiGraphics graphics) {
		int y = listBottom() + 2;
		graphics.fill(screenX + 2, y, screenX + screenW - 2, screenY + screenH - 2, 0x66000000);
		ClientSpeakers.View speaker = nearestSpeaker();
		if (speaker == null) {
			graphics.drawString(this.font, "No speaker nearby", screenX + 6, y + 3, COL_MUTED, false);
			graphics.drawString(this.font, "Place a JBL to play", screenX + 6, y + 12, COL_MUTED, false);
			return;
		}
		String line = speaker.playing()
				? "Playing: " + speaker.track().displayTitle()
				: (speaker.queueSize() > 0 ? "Paused: " + speaker.track().displayTitle() : "Ready");
		graphics.drawString(this.font, "JBL speaker", screenX + 6, y + 3, 0xFFFF8A1E, false);
		graphics.drawString(this.font, trim(line, screenW - 14), screenX + 6, y + 12, COL_WHITE, false);
	}

	/** The speaker the phone's transport buttons talk to. */
	static ClientSpeakers.View nearestSpeaker() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return null;
		return ClientSpeakers.nearest(minecraft.player.position(), 48.0);
	}

	protected String trim(String text, int width) {
		if (this.font.width(text) <= width) return text;
		return this.font.plainSubstrByWidth(text, width - 6) + "…";
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && mouseY >= listTop() && mouseY <= listBottom()
				&& mouseX >= screenX && mouseX <= screenX + screenW) {
			int index = (int) ((mouseY - listTop() + scroll) / ROW_HEIGHT);
			List<Playlist> playlists = ClientMusicLibrary.playlists();
			if (index >= 0 && index < playlists.size()) {
				playClick();
				this.minecraft.setScreen(new PlaylistDetailScreen(hand, playlists.get(index).id()));
				return true;
			}
		}
		if (button == 0 && mouseY > listBottom() && mouseY < screenY + screenH
				&& mouseX >= screenX && mouseX <= screenX + screenW) {
			ClientSpeakers.View speaker = nearestSpeaker();
			if (speaker != null) {
				playClick();
				this.minecraft.setScreen(new SpeakerScreen(speaker.pos()));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int content = ClientMusicLibrary.playlists().size() * ROW_HEIGHT;
		int maxScroll = Math.max(0, content - (listBottom() - listTop()));
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 12)));
		return true;
	}
}
