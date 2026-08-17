package com.lifeessentials.client;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.ModItems;
import com.lifeessentials.client.music.MusicClientState;
import com.lifeessentials.client.render.PhonePoseRenderer;
import com.lifeessentials.client.sound.VolumeDucker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;

@EventBusSubscriber(modid = LifeEssentials.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
	private ClientEvents() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		MusicClientState.clientTick(Minecraft.getInstance());
	}

	@SubscribeEvent
	public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
		ClientPhoneData.reset();
		MusicClientState.onDisconnect();
		VolumeDucker.reset();
	}

	/** First-person phone rendering: the vanilla two-handed map pose, with a phone. */
	@SubscribeEvent
	public static void onRenderHand(RenderHandEvent event) {
		ItemStack stack = event.getItemStack();
		if (!stack.is(ModItems.PHONE.get())) return;
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) return;

		event.setCanceled(true);
		var poseStack = event.getPoseStack();
		poseStack.pushPose();
		boolean mainHand = event.getHand() == InteractionHand.MAIN_HAND;
		if (mainHand && player.getOffhandItem().isEmpty()) {
			PhonePoseRenderer.renderBothHands(poseStack, event.getMultiBufferSource(),
					event.getPackedLight(), event.getInterpolatedPitch(),
					event.getEquipProgress(), event.getSwingProgress(), player);
		} else {
			HumanoidArm arm = mainHand ? player.getMainArm()
					: (player.getMainArm() == HumanoidArm.RIGHT ? HumanoidArm.LEFT : HumanoidArm.RIGHT);
			PhonePoseRenderer.renderOneHand(poseStack, event.getMultiBufferSource(),
					event.getPackedLight(), event.getEquipProgress(), arm,
					event.getSwingProgress(), player);
		}
		poseStack.popPose();
	}
}
