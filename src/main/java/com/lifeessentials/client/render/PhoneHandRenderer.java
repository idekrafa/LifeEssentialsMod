package com.lifeessentials.client.render;

import com.lifeessentials.LifeEssentials;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** Draws the phone's lit screen where vanilla would draw a map's paper. */
public final class PhoneHandRenderer {
	private static final ResourceLocation PHONE_FRONT =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/gui/phone_front.png");

	private PhoneHandRenderer() {
	}

	/**
	 * Mirrors the transform chain of vanilla's first-person map rendering, then
	 * draws a 64x128 phone quad instead of the 128x128 map. Full-bright: the
	 * screen glows in the dark.
	 */
	public static void renderPhoneFace(PoseStack poseStack, MultiBufferSource bufferSource) {
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
		poseStack.scale(0.38F, 0.38F, 0.38F);
		poseStack.translate(-0.5F, -0.5F, 0.0F);
		poseStack.scale(0.0078125F, 0.0078125F, 0.0078125F);

		VertexConsumer consumer = bufferSource.getBuffer(RenderType.text(PHONE_FRONT));
		Matrix4f matrix = poseStack.last().pose();
		int light = LightTexture.FULL_BRIGHT;
		consumer.addVertex(matrix, 32.0F, 128.0F, -0.01F).setColor(255, 255, 255, 255).setUv(0.0F, 1.0F).setLight(light);
		consumer.addVertex(matrix, 96.0F, 128.0F, -0.01F).setColor(255, 255, 255, 255).setUv(1.0F, 1.0F).setLight(light);
		consumer.addVertex(matrix, 96.0F, 0.0F, -0.01F).setColor(255, 255, 255, 255).setUv(1.0F, 0.0F).setLight(light);
		consumer.addVertex(matrix, 32.0F, 0.0F, -0.01F).setColor(255, 255, 255, 255).setUv(0.0F, 0.0F).setLight(light);
	}
}
