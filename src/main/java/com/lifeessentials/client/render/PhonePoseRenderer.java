package com.lifeessentials.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Replicates vanilla {@code ItemInHandRenderer}'s first-person map poses
 * (two-handed and one-handed) using only public APIs, drawing the phone's
 * screen instead of the map.
 */
public final class PhonePoseRenderer {
	private static final float PI = (float) Math.PI;

	private PhonePoseRenderer() {
	}

	public static void renderBothHands(PoseStack poseStack, MultiBufferSource bufferSource, int light,
			float pitch, float equipProgress, float swingProgress, AbstractClientPlayer player) {
		float sqrtSwing = Mth.sqrt(swingProgress);
		float bobY = -0.2F * Mth.sin(swingProgress * PI);
		float bobZ = -0.4F * Mth.sin(sqrtSwing * PI);
		poseStack.translate(0.0F, -bobY / 2.0F, bobZ);
		float angle = phoneAngle(pitch);
		poseStack.translate(0.0F, 0.04F + equipProgress * -1.2F + angle * -0.5F, -0.72F);
		poseStack.mulPose(Axis.XP.rotationDegrees(angle * -85.0F));
		if (!player.isInvisible()) {
			poseStack.pushPose();
			poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
			renderMapHand(poseStack, bufferSource, light, HumanoidArm.RIGHT, player);
			renderMapHand(poseStack, bufferSource, light, HumanoidArm.LEFT, player);
			poseStack.popPose();
		}
		float swingSin = Mth.sin(sqrtSwing * PI);
		poseStack.mulPose(Axis.XP.rotationDegrees(swingSin * 20.0F));
		poseStack.scale(2.0F, 2.0F, 2.0F);
		PhoneHandRenderer.renderPhoneFace(poseStack, bufferSource);
	}

	public static void renderOneHand(PoseStack poseStack, MultiBufferSource bufferSource, int light,
			float equipProgress, HumanoidArm arm, float swingProgress, AbstractClientPlayer player) {
		float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
		poseStack.translate(side * 0.125F, -0.125F, 0.0F);
		if (!player.isInvisible()) {
			poseStack.pushPose();
			poseStack.mulPose(Axis.ZP.rotationDegrees(side * 10.0F));
			renderPlayerArm(poseStack, bufferSource, light, equipProgress, swingProgress, arm, player);
			poseStack.popPose();
		}
		poseStack.pushPose();
		poseStack.translate(side * 0.51F, -0.08F + equipProgress * -1.2F, -0.75F);
		float sqrtSwing = Mth.sqrt(swingProgress);
		float swingSin = Mth.sin(sqrtSwing * PI);
		poseStack.translate(side * -0.5F * swingSin,
				0.4F * Mth.sin(sqrtSwing * (PI * 2.0F)) - 0.3F * swingSin,
				-0.3F * Mth.sin(swingProgress * PI));
		poseStack.mulPose(Axis.XP.rotationDegrees(swingSin * -45.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(side * swingSin * -30.0F));
		PhoneHandRenderer.renderPhoneFace(poseStack, bufferSource);
		poseStack.popPose();
	}

	/** Vanilla's renderMapHand: the bare arm reaching up to hold the map/phone. */
	private static void renderMapHand(PoseStack poseStack, MultiBufferSource bufferSource, int light,
			HumanoidArm arm, AbstractClientPlayer player) {
		if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player)
				instanceof PlayerRenderer playerRenderer)) {
			return;
		}
		poseStack.pushPose();
		float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
		poseStack.mulPose(Axis.YP.rotationDegrees(92.0F));
		poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
		poseStack.mulPose(Axis.ZP.rotationDegrees(side * -41.0F));
		poseStack.translate(side * 0.3F, -1.1F, 0.45F);
		if (arm == HumanoidArm.RIGHT) {
			playerRenderer.renderRightHand(poseStack, bufferSource, light, player);
		} else {
			playerRenderer.renderLeftHand(poseStack, bufferSource, light, player);
		}
		poseStack.popPose();
	}

	/** Vanilla's renderPlayerArm: the arm pose used while holding a map one-handed. */
	private static void renderPlayerArm(PoseStack poseStack, MultiBufferSource bufferSource, int light,
			float equipProgress, float swingProgress, HumanoidArm arm, AbstractClientPlayer player) {
		if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player)
				instanceof PlayerRenderer playerRenderer)) {
			return;
		}
		boolean right = arm != HumanoidArm.LEFT;
		float side = right ? 1.0F : -1.0F;
		float sqrtSwing = Mth.sqrt(swingProgress);
		float x = -0.3F * Mth.sin(sqrtSwing * PI);
		float y = 0.4F * Mth.sin(sqrtSwing * (PI * 2.0F));
		float z = -0.4F * Mth.sin(swingProgress * PI);
		poseStack.translate(side * (x + 0.64000005F), y + -0.6F + equipProgress * -0.6F, z + -0.71999997F);
		poseStack.mulPose(Axis.YP.rotationDegrees(side * 45.0F));
		float rollSin = Mth.sin(swingProgress * swingProgress * PI);
		float yawSin = Mth.sin(sqrtSwing * PI);
		poseStack.mulPose(Axis.YP.rotationDegrees(side * yawSin * 70.0F));
		poseStack.mulPose(Axis.ZP.rotationDegrees(side * rollSin * -20.0F));
		poseStack.translate(side * -1.0F, 3.6F, 3.5F);
		poseStack.mulPose(Axis.ZP.rotationDegrees(side * 120.0F));
		poseStack.mulPose(Axis.XP.rotationDegrees(200.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(side * -135.0F));
		poseStack.translate(side * 5.6F, 0.0F, 0.0F);
		if (right) {
			playerRenderer.renderRightHand(poseStack, bufferSource, light, player);
		} else {
			playerRenderer.renderLeftHand(poseStack, bufferSource, light, player);
		}
	}

	private static float phoneAngle(float pitch) {
		float f = 1.0F - pitch / 45.0F + 0.1F;
		f = Mth.clamp(f, 0.0F, 1.0F);
		return -Mth.cos(f * PI) * 0.5F + 0.5F;
	}
}
