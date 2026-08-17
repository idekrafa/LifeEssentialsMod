package com.lifeessentials;

import com.lifeessentials.item.AirPodsItem;
import com.lifeessentials.item.PhoneItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModItems {
	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LifeEssentials.MOD_ID);
	public static final DeferredRegister<CreativeModeTab> TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LifeEssentials.MOD_ID);

	public static final DeferredItem<Item> PHONE =
			ITEMS.register("phone", () -> new PhoneItem(new Item.Properties().stacksTo(1)));
	public static final DeferredItem<Item> AIRPODS =
			ITEMS.register("airpods", () -> new AirPodsItem(new Item.Properties().stacksTo(1)));
	public static final DeferredItem<Item> CIRCUIT_BOARD =
			ITEMS.register("circuit_board", () -> new Item(new Item.Properties()));

	public static final Supplier<CreativeModeTab> MAIN_TAB = TABS.register("main",
			() -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.lifeessentials"))
					.icon(() -> new ItemStack(PHONE.get()))
					.displayItems((parameters, output) -> {
						output.accept(PHONE.get());
						output.accept(AIRPODS.get());
						output.accept(CIRCUIT_BOARD.get());
					})
					.build());

	private ModItems() {
	}
}
