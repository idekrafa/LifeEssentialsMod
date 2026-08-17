package com.lifeessentials.item;

import java.util.List;

import com.lifeessentials.phone.PhoneDirectoryState;
import com.lifeessentials.phone.ServerPhoneManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class AirPodsItem extends Item {
	public AirPodsItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player instanceof ServerPlayer serverPlayer && serverPlayer.getServer() != null) {
			boolean wearing = PhoneDirectoryState.get(serverPlayer.getServer())
					.toggleAirpods(serverPlayer.getUUID());
			ServerPhoneManager.syncAirpods(serverPlayer, wearing);
			serverPlayer.displayClientMessage(Component.literal(wearing ? "AirPods in" : "AirPods out")
					.withStyle(ChatFormatting.WHITE), true);
			level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP,
					SoundSource.PLAYERS, 0.5f, wearing ? 1.4f : 1.0f);
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
			TooltipFlag flag) {
		tooltip.add(Component.translatable("tooltip.lifeessentials.airpods.hint")
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
