package com.lifeessentials.client;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.client.render.AirPodsLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = LifeEssentials.MOD_ID, value = Dist.CLIENT)
public final class ClientModBusEvents {
	private ClientModBusEvents() {
	}

	@SubscribeEvent
	public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
		for (PlayerSkin.Model skin : event.getSkins()) {
			if (event.getSkin(skin) instanceof PlayerRenderer renderer) {
				renderer.addLayer(new AirPodsLayer(renderer));
			}
		}
	}
}
