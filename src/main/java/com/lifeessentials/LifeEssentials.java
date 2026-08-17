package com.lifeessentials;

import com.lifeessentials.net.ModPayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(LifeEssentials.MOD_ID)
public class LifeEssentials {
	public static final String MOD_ID = "lifeessentials";
	public static final Logger LOGGER = LoggerFactory.getLogger("Life Essentials");

	public LifeEssentials(IEventBus modEventBus) {
		ModComponents.COMPONENTS.register(modEventBus);
		ModItems.ITEMS.register(modEventBus);
		ModItems.TABS.register(modEventBus);
		modEventBus.addListener(ModPayloads::register);

		LOGGER.info("Life Essentials initialized — enjoy your iPhone!");
	}
}
