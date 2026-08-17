package com.lifeessentials.item;

import java.util.List;

import com.lifeessentials.ModComponents;
import com.lifeessentials.client.PhoneClientOpener;
import com.lifeessentials.phone.PhoneDirectoryState;
import com.lifeessentials.phone.PhoneNumbers;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class PhoneItem extends Item {
	public PhoneItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level instanceof ServerLevel serverLevel) {
			ensureNumber(stack, serverLevel);
		} else {
			// client side: open the phone UI (guarded call — never runs on a dedicated server)
			PhoneClientOpener.openHome(hand);
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
		if (level instanceof ServerLevel serverLevel && !stack.has(ModComponents.PHONE_NUMBER.get())) {
			ensureNumber(stack, serverLevel);
		}
	}

	private static void ensureNumber(ItemStack stack, ServerLevel level) {
		if (!stack.has(ModComponents.PHONE_NUMBER.get())) {
			stack.set(ModComponents.PHONE_NUMBER.get(),
					PhoneDirectoryState.get(level.getServer()).assignNumber());
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
			TooltipFlag flag) {
		String number = stack.get(ModComponents.PHONE_NUMBER.get());
		if (number != null) {
			tooltip.add(Component.translatable("tooltip.lifeessentials.phone.number",
					PhoneNumbers.pretty(number)).withStyle(ChatFormatting.AQUA));
		} else {
			tooltip.add(Component.translatable("tooltip.lifeessentials.phone.no_number")
					.withStyle(ChatFormatting.GRAY));
		}
		tooltip.add(Component.translatable("tooltip.lifeessentials.phone.hint")
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
