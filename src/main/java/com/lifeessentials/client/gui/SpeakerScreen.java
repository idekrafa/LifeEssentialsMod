package com.lifeessentials.client.gui;

import java.util.List;
import java.util.Locale;

import com.lifeessentials.client.ClientMusicLibrary;
import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.ClientSpeakers;
import com.lifeessentials.client.audio.BackendLoader;
import com.lifeessentials.client.audio.MediaTools;
import com.lifeessentials.music.Playlist;
import com.lifeessentials.music.Track;
import com.lifeessentials.net.MusicPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** The speaker's own deck — everything the block can do, in one panel. */
public class SpeakerScreen extends Screen {
	private static final int PANEL_W = 280;
	private static final int PANEL_H = 200;
	private static final int ROW_HEIGHT = 20;
	/** Width of the left-hand deck column, before the divider. */
	private static final int COLUMN_W = 150;

	private static final int COL_PANEL = 0xFF16161A;
	private static final int COL_EDGE = 0xFF2C2C34;
	private static final int COL_ORANGE = 0xFFFF6B1E;
	private static final int COL_WHITE = 0xFFF2F5FA;
	private static final int COL_MUTED = 0xFF9BA3B2;

	private final BlockPos pos;

	private int left;
	private int top;
	private int listTop;
	private int listBottom;
	private int scroll = 0;
	private int missingTicks = 0;

	private Button playButton;
	private Button shuffleButton;
	private Button repeatButton;
	/** False until the sliders were built from a real sync rather than defaults. */
	private boolean slidersLive = false;

	public SpeakerScreen(BlockPos pos) {
		super(Component.literal("JBL Speaker"));
		this.pos = pos;
	}

	private ClientSpeakers.View view() {
		return ClientSpeakers.get(pos);
	}

	private void send(String action, String arg, int value) {
		ClientNet.send(new MusicPayloads.SpeakerCommandC2S(pos, action, arg, value));
	}

	@Override
	protected void init() {
		left = (this.width - PANEL_W) / 2;
		top = Math.max(4, (this.height - PANEL_H) / 2);
		listTop = top + 38;
		listBottom = top + PANEL_H - 10;

		int col = left + 10;
		int colWidth = COLUMN_W;

		int buttonWidth = (colWidth - 9) / 4;
		addRenderableWidget(Button.builder(Component.literal("<<"), b -> send("prev", "", 0))
				.bounds(col, top + 76, buttonWidth, 20).build());
		playButton = addRenderableWidget(Button.builder(Component.literal("Play"),
						b -> send("toggle", "", 0))
				.bounds(col + buttonWidth + 3, top + 76, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">>"), b -> send("next", "", 0))
				.bounds(col + (buttonWidth + 3) * 2, top + 76, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Stop"), b -> send("stop", "", 0))
				.bounds(col + (buttonWidth + 3) * 3, top + 76, buttonWidth, 20).build());

		ClientSpeakers.View view = view();
		slidersLive = view != null;
		int volume = view == null ? 80 : view.volume();
		int range = view == null ? 32 : view.range();
		addRenderableWidget(new SpeakerSlider(col, top + 100, colWidth, 18,
				"Volume", volume, 0, 100, value -> send("volume", "", value)));
		addRenderableWidget(new SpeakerSlider(col, top + 122, colWidth, 18,
				"Range", range, 8, 64, value -> send("range", "", value)));

		int halfWidth = (colWidth - 4) / 2;
		shuffleButton = addRenderableWidget(Button.builder(Component.literal("Shuffle"),
						b -> send("shuffle", "", 0))
				.bounds(col, top + 144, halfWidth, 18).build());
		repeatButton = addRenderableWidget(Button.builder(Component.literal("Repeat"),
						b -> send("repeat", "", 0))
				.bounds(col + halfWidth + 4, top + 144, halfWidth, 18).build());

		send("refresh", "", 0);
		ClientNet.send(MusicPayloads.LibraryRequestC2S.INSTANCE);
		updateWidgets();
	}

	private void updateWidgets() {
		ClientSpeakers.View view = view();
		boolean playing = view != null && view.playing();
		if (playButton != null) {
			playButton.setMessage(Component.literal(playing ? "Pause" : "Play"));
		}
		if (shuffleButton != null) {
			shuffleButton.setMessage(Component.literal(view != null && view.shuffle()
					? "Shuffle: On" : "Shuffle: Off"));
		}
		if (repeatButton != null) {
			repeatButton.setMessage(Component.literal(repeatLabel(view == null ? 1 : view.repeat())));
		}
	}

	private static String repeatLabel(int mode) {
		return switch (mode) {
			case 0 -> "Repeat: Off";
			case 2 -> "Repeat: One";
			default -> "Repeat: All";
		};
	}

	@Override
	public void tick() {
		ClientSpeakers.View view = view();
		if (view == null) {
			if (++missingTicks > 200 && this.minecraft != null) {
				this.minecraft.setScreen(null);
			}
			return;
		}
		missingTicks = 0;
		if (!slidersLive) {
			// the deck opened before the first sync landed — rebuild off real values
			rebuildWidgets();
			return;
		}
		updateWidgets();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ----------------------------------------------------------------- render

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		drawPanel(graphics);
		drawNowPlaying(graphics);
		drawPlaylists(graphics, mouseX, mouseY);
	}

	private void drawPanel(GuiGraphics graphics) {
		graphics.fill(left - 2, top - 2, left + PANEL_W + 2, top + PANEL_H + 2, COL_EDGE);
		graphics.fill(left, top, left + PANEL_W, top + PANEL_H, COL_PANEL);
		// JBL badge
		graphics.fill(left + 10, top + 8, left + 42, top + 22, COL_ORANGE);
		graphics.drawString(this.font, "JBL", left + 17, top + 11, 0xFF16161A, false);
		graphics.drawString(this.font, "Bluetooth Speaker", left + 48, top + 11, COL_WHITE, false);
		ClientSpeakers.View view = view();
		// right-aligned, and trimmed so far-out coordinates can't run into the title
		String where = trim(view == null ? "connecting…"
				: pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), PANEL_W - 150);
		graphics.drawString(this.font, where, left + PANEL_W - 8 - this.font.width(where),
				top + 11, COL_MUTED, false);
		// column divider
		graphics.fill(left + 164, top + 30, left + 165, top + PANEL_H - 8, COL_EDGE);
	}

	private void drawNowPlaying(GuiGraphics graphics) {
		int x = left + 10;
		ClientSpeakers.View view = view();
		if (view == null) {
			graphics.drawString(this.font, "Connecting to the speaker…", x, top + 38, COL_MUTED, false);
			return;
		}
		Track track = view.track();
		boolean idle = track.isEmpty();
		graphics.drawString(this.font, trim(idle ? "Nothing queued" : track.displayTitle(), COLUMN_W),
				x, top + 38, COL_WHITE, false);
		String meta = idle
				? (view.playlistName().isEmpty() ? "Pick a playlist on the right" : view.playlistName())
				: (track.artist().isBlank() ? track.source().label()
						: track.artist() + " · " + track.source().label());
		graphics.drawString(this.font, trim(meta, COLUMN_W), x, top + 49, COL_MUTED, false);

		// progress
		long position = view.positionMs();
		int duration = track.durationSeconds();
		int barWidth = COLUMN_W;
		graphics.fill(x, top + 62, x + barWidth, top + 65, 0xFF32323C);
		float progress = duration > 0
				? Math.min(1.0f, position / (duration * 1000.0f))
				: (view.playing() ? (System.currentTimeMillis() % 3000) / 3000.0f : 0.0f);
		graphics.fill(x, top + 62, x + (int) (barWidth * progress), top + 65, COL_ORANGE);
		String elapsed = clock(position / 1000);
		String total = duration > 0 ? clock(duration) : "--:--";
		graphics.drawString(this.font, elapsed, x, top + 67, COL_MUTED, false);
		String right = view.queueSize() > 0
				? (view.trackIndex() % Math.max(1, view.queueSize()) + 1) + "/" + view.queueSize()
						+ "   " + total
				: total;
		graphics.drawString(this.font, right, x + barWidth - this.font.width(right),
				top + 67, COL_MUTED, false);

		// the decoder line is noise unless the built-in engine failed to start
		if (!BackendLoader.isReady()) {
			graphics.drawString(this.font, trim(MediaTools.status(), COLUMN_W), x, top + 168,
					COL_MUTED, false);
		}
		graphics.drawString(this.font, trim("Heard " + view.range() + " blocks away", COLUMN_W),
				x, top + 180, COL_MUTED, false);
	}

	private void drawPlaylists(GuiGraphics graphics, int mouseX, int mouseY) {
		int x = left + 172;
		int width = PANEL_W - 182;
		graphics.drawString(this.font, "Playlists", x, top + 30, COL_WHITE, false);

		List<Playlist> playlists = ClientMusicLibrary.playlists();
		if (playlists.isEmpty()) {
			graphics.drawString(this.font, "Make one on", x, listTop + 6, COL_MUTED, false);
			graphics.drawString(this.font, "your iPhone", x, listTop + 17, COL_MUTED, false);
			return;
		}

		ClientSpeakers.View view = view();
		String activeId = view == null ? "" : view.playlistId();

		graphics.enableScissor(x, listTop, x + width, listBottom);
		int y = listTop - scroll;
		for (Playlist playlist : playlists) {
			if (y + ROW_HEIGHT >= listTop && y <= listBottom) {
				boolean hover = mouseX >= x && mouseX <= x + width
						&& mouseY >= y && mouseY < y + ROW_HEIGHT - 2
						&& mouseY >= listTop && mouseY <= listBottom;
				boolean active = playlist.id().equals(activeId);
				graphics.fill(x, y, x + width, y + ROW_HEIGHT - 2,
						active ? 0x44FF6B1E : (hover ? 0x44FFFFFF : 0x22FFFFFF));
				if (active) {
					graphics.fill(x, y, x + 2, y + ROW_HEIGHT - 2, COL_ORANGE);
				}
				graphics.drawString(this.font, trim(playlist.name(), width - 8),
						x + 5, y + 2, COL_WHITE, false);
				graphics.drawString(this.font, playlist.size() + " tracks",
						x + 5, y + 11, COL_MUTED, false);
			}
			y += ROW_HEIGHT;
		}
		graphics.disableScissor();
	}

	private String trim(String text, int width) {
		if (this.font.width(text) <= width) return text;
		return this.font.plainSubstrByWidth(text, Math.max(6, width - 6)) + "…";
	}

	private static String clock(long seconds) {
		long safe = Math.max(0, seconds);
		return (safe / 60) + ":" + String.format(Locale.ROOT, "%02d", safe % 60);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int x = left + 172;
		int width = PANEL_W - 182;
		if (button == 0 && mouseX >= x && mouseX <= x + width
				&& mouseY >= listTop && mouseY <= listBottom) {
			int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
			List<Playlist> playlists = ClientMusicLibrary.playlists();
			if (index >= 0 && index < playlists.size()) {
				send("play_playlist", playlists.get(index).id(), 0);
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int content = ClientMusicLibrary.playlists().size() * ROW_HEIGHT;
		int maxScroll = Math.max(0, content - (listBottom - listTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 10)));
		return true;
	}

	/** Slider that only tells the server about the new value once you let go. */
	private static class SpeakerSlider extends AbstractSliderButton {
		private final String label;
		private final int min;
		private final int max;
		private final java.util.function.IntConsumer onCommit;

		SpeakerSlider(int x, int y, int width, int height, String label, int initial,
				int min, int max, java.util.function.IntConsumer onCommit) {
			super(x, y, width, height, Component.empty(),
					(double) (initial - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.onCommit = onCommit;
			updateMessage();
		}

		private int intValue() {
			return min + (int) Math.round(this.value * (max - min));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + intValue()
					+ ("Volume".equals(label) ? "%" : " blocks")));
		}

		@Override
		protected void applyValue() {
			// committed in onRelease so dragging doesn't flood the server
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			super.onRelease(mouseX, mouseY);
			onCommit.accept(intValue());
		}
	}
}
