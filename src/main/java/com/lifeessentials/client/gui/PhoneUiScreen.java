package com.lifeessentials.client.gui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.lifeessentials.ModComponents;
import com.lifeessentials.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Base screen that draws the phone body/chrome; apps render inside the screen area. */
public abstract class PhoneUiScreen extends Screen {
	protected static final int PHONE_W = 150;
	protected static final int PHONE_H = 250;

	protected static final int COL_BODY = 0xFF14141A;
	protected static final int COL_FRAME = 0xFF2C2C36;
	protected static final int COL_SCREEN_TOP = 0xFF15265C;
	protected static final int COL_SCREEN_BOTTOM = 0xFF2BA8BD;
	protected static final int COL_WHITE = 0xFFF2F5FA;
	protected static final int COL_MUTED = 0xFFB9C2D0;

	protected final InteractionHand hand;
	protected int phoneLeft;
	protected int phoneTop;
	protected int screenX;
	protected int screenY;
	protected int screenW;
	protected int screenH;

	protected PhoneUiScreen(Component title, InteractionHand hand) {
		super(title);
		this.hand = hand;
	}

	@Override
	protected void init() {
		phoneLeft = (this.width - PHONE_W) / 2;
		phoneTop = Math.max(4, (this.height - PHONE_H) / 2);
		screenX = phoneLeft + 8;
		screenY = phoneTop + 20;
		screenW = PHONE_W - 16;
		screenH = PHONE_H - 38;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick);
		drawPhoneChrome(graphics, mouseX, mouseY);
		renderScreenContent(graphics, mouseX, mouseY, partialTick);
	}

	private void drawPhoneChrome(GuiGraphics graphics, int mouseX, int mouseY) {
		// body with stepped "rounded" corners
		graphics.fill(phoneLeft + 4, phoneTop, phoneLeft + PHONE_W - 4, phoneTop + PHONE_H, COL_BODY);
		graphics.fill(phoneLeft + 2, phoneTop + 2, phoneLeft + PHONE_W - 2, phoneTop + PHONE_H - 2, COL_BODY);
		graphics.fill(phoneLeft, phoneTop + 4, phoneLeft + PHONE_W, phoneTop + PHONE_H - 4, COL_BODY);
		// frame around the display
		graphics.fill(screenX - 2, screenY - 14, screenX + screenW + 2, screenY + screenH + 2, COL_FRAME);
		// display (status bar strip + app area)
		graphics.fillGradient(screenX - 1, screenY - 13, screenX + screenW + 1, screenY + screenH + 1,
				COL_SCREEN_TOP, COL_SCREEN_BOTTOM);

		// status bar
		String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
		graphics.drawString(this.font, time, screenX + 3, screenY - 11, COL_WHITE, false);
		int bx = screenX + screenW - 16;
		graphics.fill(bx, screenY - 10, bx + 11, screenY - 5, COL_WHITE);
		graphics.fill(bx + 1, screenY - 9, bx + 9, screenY - 6, 0xFF39D05C);
		graphics.fill(bx + 11, screenY - 8, bx + 12, screenY - 7, COL_WHITE);
		for (int i = 0; i < 3; i++) { // signal bars
			int sx = bx - 10 + i * 3;
			graphics.fill(sx, screenY - 5 - (i + 1) * 2, sx + 2, screenY - 5, COL_WHITE);
		}

		// home pill
		int hy = phoneTop + PHONE_H - 11;
		boolean hover = isInHomePill(mouseX, mouseY);
		graphics.fill(phoneLeft + 55, hy, phoneLeft + 95, hy + 4, hover ? 0xFFFFFFFF : 0xFF9BA1AE);
	}

	protected abstract void renderScreenContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

	protected boolean isInHomePill(double mouseX, double mouseY) {
		int hy = phoneTop + PHONE_H - 13;
		return mouseX >= phoneLeft + 50 && mouseX <= phoneLeft + 100 && mouseY >= hy && mouseY <= hy + 8;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && isInHomePill(mouseX, mouseY)) {
			playClick();
			goBack();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			goBack();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	/** Home pill / ESC. The home screen closes the phone; apps go back home. */
	protected void goBack() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new HomeScreen(hand));
		}
	}

	protected void playClick() {
		if (this.minecraft != null) {
			this.minecraft.getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		}
	}

	@Override
	public void tick() {
		// phone gone from hands -> close the UI
		if (this.minecraft != null && phoneStack().isEmpty()) {
			this.minecraft.setScreen(null);
		}
	}

	protected ItemStack phoneStack() {
		if (this.minecraft == null || this.minecraft.player == null) return ItemStack.EMPTY;
		ItemStack stack = this.minecraft.player.getItemInHand(hand);
		if (stack.is(ModItems.PHONE.get())) return stack;
		InteractionHand other = hand == InteractionHand.MAIN_HAND
				? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		stack = this.minecraft.player.getItemInHand(other);
		return stack.is(ModItems.PHONE.get()) ? stack : ItemStack.EMPTY;
	}

	/** 10-digit number of the held phone, or null while it is still activating. */
	protected String myNumber() {
		ItemStack stack = phoneStack();
		return stack.isEmpty() ? null : stack.get(ModComponents.PHONE_NUMBER.get());
	}
}
