package com.lifeessentials.client.render;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.client.ClientPhoneData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Renders little cube AirPods on the ears of any player wearing them. */
public class AirPodsLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, "textures/entity/airpods.png");

	private final ModelPart pods;

	public AirPodsLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
		this.pods = buildModel();
	}

	private static ModelPart buildModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		// head cube spans x/z -4..4, y -8..0 (pivot at the neck)
		root.addOrReplaceChild("left", CubeListBuilder.create()
						.texOffs(0, 0).addBox(4.0F, -5.2F, -0.8F, 1.2F, 1.6F, 1.6F)
						.texOffs(0, 6).addBox(4.25F, -3.8F, -0.55F, 0.7F, 2.4F, 0.7F),
				PartPose.ZERO);
		root.addOrReplaceChild("right", CubeListBuilder.create()
						.texOffs(8, 0).addBox(-5.2F, -5.2F, -0.8F, 1.2F, 1.6F, 1.6F)
						.texOffs(8, 6).addBox(-4.95F, -3.8F, -0.55F, 0.7F, 2.4F, 0.7F),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 16, 16).bakeRoot();
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
			AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick,
			float ageInTicks, float netHeadYaw, float headPitch) {
		if (player.isInvisible() || !ClientPhoneData.isWearing(player.getUUID())) {
			return;
		}
		poseStack.pushPose();
		this.getParentModel().head.translateAndRotate(poseStack);
		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
		pods.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}
}
