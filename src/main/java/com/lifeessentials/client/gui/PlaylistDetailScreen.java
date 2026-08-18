package com.lifeessentials.client.gui;

import java.util.List;

import com.lifeessentials.client.ClientMusicLibrary;
import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.ClientSpeakers;
import com.lifeessentials.music.Playlist;
import com.lifeessentials.music.Track;
import com.lifeessentials.net.MusicPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/** One playlist: reorder it, prune it, and send it to the speaker you're stood by. */
public class PlaylistDetailScreen extends PhoneUiScreen {
	private static final int ROW_HEIGHT = 22;
	/** Width of the up / down / remove strip on the right of each row. */
	private static final int CONTROL_WIDTH = 11;

	private final String playlistId;
	private int scroll = 0;

	public PlaylistDetailScreen(InteractionHand hand, String playlistId) {
		super(Component.literal("Playlist"), hand);
		this.playlistId = playlistId;
	}

	@Override
	protected void init() {
		super.init();
		int half = (screenW - 10) / 2;
		addRenderableWidget(Button.builder(Component.literal("+ Music"), b -> {
			playClick();
			this.minecraft.setScreen(new AddTrackScreen(hand, playlistId));
		}).bounds(screenX + 4, screenY + 15, half, 15).build());
		addRenderableWidget(Button.builder(Component.literal("Play here"), b -> playFrom(0))
				.bounds(screenX + 6 + half, screenY + 15, half, 15).build());
		addRenderableWidget(Button.builder(Component.literal("Delete playlist"), b -> {
			playClick();
			ClientNet.send(new MusicPayloads.LibraryCommandC2S("delete", playlistId, "", 0));
			this.minecraft.setScreen(new PlaylistsAppScreen(hand));
		}).bounds(screenX + 4, screenY + screenH - 19, screenW - 8, 15).build());
	}

	private Playlist playlist() {
		return ClientMusicLibrary.playlist(playlistId);
	}

	private List<Track> tracks() {
		return ClientMusicLibrary.resolve(playlistId);
	}

	private void playFrom(int index) {
		ClientSpeakers.View speaker = PlaylistsAppScreen.nearestSpeaker();
		if (speaker == null) {
			if (this.minecraft != null && this.minecraft.player != null) {
				this.minecraft.player.displayClientMessage(Component.literal(
								"No JBL speaker within 48 blocks — place one first")
						.withStyle(ChatFormatting.YELLOW), true);
			}
			return;
		}
		if (tracks().isEmpty()) return;
		playClick();
		ClientNet.send(new MusicPayloads.SpeakerCommandC2S(speaker.pos(), "play_playlist",
				playlistId, index));
	}

	private int listTop() {
		return screenY + 34;
	}

	private int listBottom() {
		return screenY + screenH - 23;
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		Playlist playlist = playlist();
		String title = playlist == null ? "Playlist" : playlist.name();
		graphics.drawCenteredString(this.font, Component.literal(trim(title, screenW - 6)),
				screenX + screenW / 2, screenY + 3, COL_WHITE);

		List<Track> tracks = tracks();
		if (tracks.isEmpty()) {
			graphics.drawCenteredString(this.font, Component.literal("Empty — tap + Music"),
					screenX + screenW / 2, listTop() + 16, COL_MUTED);
			return;
		}

		graphics.enableScissor(screenX, listTop(), screenX + screenW, listBottom());
		int y = listTop() - scroll;
		int controlsX = screenX + screenW - 2 - CONTROL_WIDTH * 3;
		for (int i = 0; i < tracks.size(); i++) {
			if (y + ROW_HEIGHT >= listTop() && y <= listBottom()) {
				Track track = tracks.get(i);
				boolean hover = mouseY >= y && mouseY < y + ROW_HEIGHT - 2
						&& mouseY >= listTop() && mouseY <= listBottom()
						&& mouseX >= screenX + 2 && mouseX < controlsX;
				graphics.fill(screenX + 2, y, screenX + screenW - 2, y + ROW_HEIGHT - 2,
						hover ? 0x55FFFFFF : 0x33000000);
				graphics.drawString(this.font, trim(track.displayTitle(), controlsX - screenX - 10),
						screenX + 6, y + 2, COL_WHITE, false);
				String meta = track.source().label();
				if (!track.artist().isBlank()) meta = track.artist() + " · " + meta;
				if (!track.clock().isEmpty()) meta += " · " + track.clock();
				graphics.drawString(this.font, trim(meta, controlsX - screenX - 10),
						screenX + 6, y + 11, COL_MUTED, false);

				drawControl(graphics, controlsX, y, mouseX, mouseY, "^");
				drawControl(graphics, controlsX + CONTROL_WIDTH, y, mouseX, mouseY, "v");
				drawControl(graphics, controlsX + CONTROL_WIDTH * 2, y, mouseX, mouseY, "x");
			}
			y += ROW_HEIGHT;
		}
		graphics.disableScissor();
	}

	private void drawControl(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, String glyph) {
		boolean hover = mouseX >= x && mouseX < x + CONTROL_WIDTH
				&& mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
		graphics.fill(x, y, x + CONTROL_WIDTH - 1, y + ROW_HEIGHT - 2, hover ? 0x66FFFFFF : 0x22FFFFFF);
		graphics.drawCenteredString(this.font, Component.literal(glyph),
				x + CONTROL_WIDTH / 2, y + 6, "x".equals(glyph) ? 0xFFFF8A8A : COL_WHITE);
	}

	protected String trim(String text, int width) {
		if (this.font.width(text) <= width) return text;
		return this.font.plainSubstrByWidth(text, Math.max(6, width - 6)) + "…";
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && mouseY >= listTop() && mouseY <= listBottom()
				&& mouseX >= screenX && mouseX <= screenX + screenW) {
			int index = (int) ((mouseY - listTop() + scroll) / ROW_HEIGHT);
			List<Track> tracks = tracks();
			if (index >= 0 && index < tracks.size()) {
				int controlsX = screenX + screenW - 2 - CONTROL_WIDTH * 3;
				if (mouseX >= controlsX) {
					int slot = (int) ((mouseX - controlsX) / CONTROL_WIDTH);
					String action = switch (slot) {
						case 0 -> "move_up";
						case 1 -> "move_down";
						default -> "remove_track";
					};
					playClick();
					ClientNet.send(new MusicPayloads.LibraryCommandC2S(action, playlistId, "", index));
				} else {
					playFrom(index);
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int content = tracks().size() * ROW_HEIGHT;
		int maxScroll = Math.max(0, content - (listBottom() - listTop()));
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 12)));
		return true;
	}

	@Override
	protected void goBack() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new PlaylistsAppScreen(hand));
		}
	}
}
