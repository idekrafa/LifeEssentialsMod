package com.lifeessentials.client.gui;

import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.ClientPhoneData;
import com.lifeessentials.net.ModPayloads;
import com.lifeessentials.phone.PhoneNumbers;
import com.lifeessentials.phone.TextMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

public class NewMessageScreen extends PhoneUiScreen {
	private EditBox numberField;
	private EditBox messageField;
	private String error = null;

	public NewMessageScreen(InteractionHand hand) {
		super(Component.literal("New Message"), hand);
	}

	@Override
	protected void init() {
		super.init();
		numberField = new EditBox(this.font, screenX + 4, screenY + 28, screenW - 8, 14,
				Component.literal("Number"));
		numberField.setMaxLength(20);
		numberField.setHint(Component.literal("(238) 230-2939"));
		addRenderableWidget(numberField);

		messageField = new EditBox(this.font, screenX + 4, screenY + 62, screenW - 8, 14,
				Component.literal("Message"));
		messageField.setMaxLength(256);
		messageField.setHint(Component.literal("Message…"));
		addRenderableWidget(messageField);

		addRenderableWidget(Button.builder(Component.literal("Send"), b -> send())
				.bounds(screenX + 4, screenY + 86, screenW - 8, 18).build());
		setInitialFocus(numberField);
	}

	private void send() {
		String to = PhoneNumbers.normalize(numberField.getValue());
		String text = messageField.getValue().strip();
		String mine = myNumber();
		if (to == null) {
			error = "Enter a valid 10-digit number";
			return;
		}
		if (text.isEmpty()) {
			error = "Write a message first";
			return;
		}
		if (mine == null) {
			error = "Phone is still activating…";
			return;
		}
		ClientNet.send(new ModPayloads.SendMessageC2S(mine, to, text));
		ClientPhoneData.appendMessage(mine, to, new TextMessage(mine, text, System.currentTimeMillis()));
		playClick();
		this.minecraft.setScreen(new ConversationScreen(hand, to));
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
				&& messageField != null && messageField.isFocused()) {
			send();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	protected void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawCenteredString(this.font, Component.literal("New Message"),
				screenX + screenW / 2, screenY + 3, COL_WHITE);
		graphics.drawString(this.font, "To:", screenX + 4, screenY + 18, COL_MUTED, false);
		graphics.drawString(this.font, "Text:", screenX + 4, screenY + 52, COL_MUTED, false);
		if (error != null) {
			graphics.drawCenteredString(this.font, Component.literal(error),
					screenX + screenW / 2, screenY + 110, 0xFFFF6666);
		}
	}

	@Override
	protected void goBack() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new MessagesAppScreen(hand));
		}
	}
}
