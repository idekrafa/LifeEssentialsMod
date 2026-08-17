package com.lifeessentials;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModComponents {
	public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
			DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LifeEssentials.MOD_ID);

	/** The phone's number as a 10-digit string, e.g. "2382302939". */
	public static final Supplier<DataComponentType<String>> PHONE_NUMBER =
			COMPONENTS.register("phone_number", () -> DataComponentType.<String>builder()
					.persistent(Codec.STRING)
					.networkSynchronized(ByteBufCodecs.STRING_UTF8)
					.build());

	private ModComponents() {
	}
}
