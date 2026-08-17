package com.lifeessentials.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.ClientPhoneData;
import com.lifeessentials.net.ModPayloads;
import com.lifeessentials.phone.PhoneNumbers;
import com.lifeessentials.phone.TextMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

public class MessagesAppScreen extends PhoneUiScreen {
	private static final int ROW_HEIGHT = 26;

	private int scroll = 0;
	private int refreshTimer = 0;

	public MessagesAppScreen(InteractionHand hand) {
		super(Component.literal("Messages"), hand);
	}

	@Override
	protected void init() {
		super.init();
		addRenderableWidget(Button.builder(Component.literal("New Message"), b -> {
			playClick();
			this.minecraft.setScreen(new NewMessageScreen(hand));
		}).bounds(screenX + 4, screenY + 14, screenW - 8, 16).build());
		requestConversations();
	}

	private void requestConversations() {
		String number = myNumber();
		if (number != null) {
			ClientNet.send(new ModPayloads.RequestConversationsC2S(number));
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (++refreshTimer % 60 == 0) {
			requestConversations();
		}
	}

	private List<Map.Entry<String, List<TextMessage>>> rows() {
		return new ArrayList<>(ClientPhoneData.conversations.entrySet());
	}

	private int listTop() {
		return screenY + 34;
	}

	private int listBottom() {
		return screenY + screenH - 2;
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawCenteredString(this.font, Component.literal("Messages"),
				screenX + screenW / 2, screenY + 3, COL_WHITE);

		List<Map.Entry<String, List<TextMessage>>> rows = rows();
		if (rows.isEmpty()) {
			graphics.drawCenteredString(this.font, Component.literal("No conversations yet"),
					screenX + screenW / 2, listTop() + 20, COL_MUTED);
			return;
		}

		graphics.enableScissor(screenX, listTop(), screenX + screenW, listBottom());
		int y = listTop() - scroll;
		for (Map.Entry<String, List<TextMessage>> row : rows) {
			if (y + ROW_HEIGHT >= listTop() && y <= listBottom()) {
				boolean hover = mouseX >= screenX + 2 && mouseX <= screenX + screenW - 2
						&& mouseY >= y && mouseY < y + ROW_HEIGHT - 2 && mouseY >= listTop()
						&& mouseY <= listBottom();
				graphics.fill(screenX + 2, y, screenX + screenW - 2, y + ROW_HEIGHT - 2,
						hover ? 0x55FFFFFF : 0x33000000);
				graphics.drawString(this.font, PhoneNumbers.pretty(row.getKey()),
						screenX + 6, y + 3, COL_WHITE, false);
				List<TextMessage> messages = row.getValue();
				String preview = messages.isEmpty() ? "" : messages.get(messages.size() - 1).text();
				preview = this.font.plainSubstrByWidth(preview, screenW - 20);
				graphics.drawString(this.font, preview, screenX + 6, y + 14, COL_MUTED, false);
				if (ClientPhoneData.unread.contains(row.getKey())) {
					graphics.fill(screenX + screenW - 10, y + 4, screenX + screenW - 5, y + 9, 0xFFE8352C);
				}
			}
			y += ROW_HEIGHT;
		}
		graphics.disableScissor();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && mouseY >= listTop() && mouseY <= listBottom()
				&& mouseX >= screenX && mouseX <= screenX + screenW) {
			int index = (int) ((mouseY - listTop() + scroll) / ROW_HEIGHT);
			List<Map.Entry<String, List<TextMessage>>> rows = rows();
			if (index >= 0 && index < rows.size()) {
				playClick();
				this.minecraft.setScreen(new ConversationScreen(hand, rows.get(index).getKey()));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int contentHeight = rows().size() * ROW_HEIGHT;
		int visible = listBottom() - listTop();
		int maxScroll = Math.max(0, contentHeight - visible);
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 12)));
		return true;
	}
}
