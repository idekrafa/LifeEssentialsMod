package com.lifeessentials.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.ClientPhoneData;
import com.lifeessentials.net.ModPayloads;
import com.lifeessentials.phone.PhoneNumbers;
import com.lifeessentials.phone.TextMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

public class ConversationScreen extends PhoneUiScreen {
	private final String otherNumber;
	private EditBox input;
	private int scroll = 0; // 0 = pinned to the newest message

	public ConversationScreen(InteractionHand hand, String otherNumber) {
		super(Component.literal("Conversation"), hand);
		this.otherNumber = otherNumber;
	}

	public String otherNumber() {
		return otherNumber;
	}

	@Override
	protected void init() {
		super.init();
		ClientPhoneData.unread.remove(otherNumber);
		input = new EditBox(this.font, screenX + 4, inputTop(), screenW - 40, 14,
				Component.literal("Message"));
		input.setMaxLength(256);
		input.setHint(Component.literal("Message…"));
		addRenderableWidget(input);
		addRenderableWidget(Button.builder(Component.literal("Send"), b -> send())
				.bounds(screenX + screenW - 34, inputTop() - 1, 34, 16).build());
		setInitialFocus(input);
	}

	private int inputTop() {
		return screenY + screenH - 16;
	}

	private int listTop() {
		return screenY + 14;
	}

	private int listBottom() {
		return inputTop() - 4;
	}

	private void send() {
		String text = input.getValue().strip();
		String mine = myNumber();
		if (text.isEmpty() || mine == null) return;
		ClientNet.send(new ModPayloads.SendMessageC2S(mine, otherNumber, text));
		// optimistic local echo; the server stores the authoritative copy
		ClientPhoneData.appendMessage(mine, otherNumber,
				new TextMessage(mine, text, System.currentTimeMillis()));
		input.setValue("");
		scroll = 0;
		playClick();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
				&& input != null && input.isFocused()) {
			send();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private record Bubble(List<FormattedCharSequence> lines, boolean mine) {
	}

	private List<Bubble> buildBubbles() {
		List<Bubble> bubbles = new ArrayList<>();
		String mine = myNumber();
		List<TextMessage> messages = ClientPhoneData.conversations.get(otherNumber);
		if (messages == null) return bubbles;
		for (TextMessage message : messages) {
			boolean isMine = message.from().equals(mine);
			bubbles.add(new Bubble(this.font.split(Component.literal(message.text()), 86), isMine));
		}
		return bubbles;
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawCenteredString(this.font, Component.literal(PhoneNumbers.pretty(otherNumber)),
				screenX + screenW / 2, screenY + 3, COL_WHITE);

		List<Bubble> bubbles = buildBubbles();
		graphics.enableScissor(screenX, listTop(), screenX + screenW, listBottom());
		// lay out bottom-up so the newest message hugs the input box
		int y = listBottom() + scroll;
		for (int i = bubbles.size() - 1; i >= 0; i--) {
			Bubble bubble = bubbles.get(i);
			int textWidth = 0;
			for (FormattedCharSequence line : bubble.lines()) {
				textWidth = Math.max(textWidth, this.font.width(line));
			}
			int bubbleHeight = bubble.lines().size() * 9 + 5;
			int bubbleWidth = textWidth + 8;
			y -= bubbleHeight + 3;
			int x = bubble.mine() ? screenX + screenW - 6 - bubbleWidth : screenX + 6;
			int color = bubble.mine() ? 0xFF2E9E44 : 0xFF3A3A46;
			if (y + bubbleHeight >= listTop() && y <= listBottom()) {
				graphics.fill(x, y, x + bubbleWidth, y + bubbleHeight, color);
				int textY = y + 3;
				for (FormattedCharSequence line : bubble.lines()) {
					graphics.drawString(this.font, line, x + 4, textY, COL_WHITE, false);
					textY += 9;
				}
			}
		}
		graphics.disableScissor();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int contentHeight = 0;
		for (Bubble bubble : buildBubbles()) {
			contentHeight += bubble.lines().size() * 9 + 8;
		}
		int visible = listBottom() - listTop();
		int maxScroll = Math.max(0, contentHeight - visible);
		scroll = Math.max(0, Math.min(maxScroll, scroll + (int) (scrollY * 12)));
		return true;
	}

	@Override
	protected void goBack() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new MessagesAppScreen(hand));
		}
	}
}
