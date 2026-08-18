package com.lifeessentials.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** Block item for the speaker — just carries the tooltip. */
public class JblSpeakerItem extends BlockItem {
	public JblSpeakerItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
			TooltipFlag flag) {
		tooltip.add(Component.translatable("tooltip.lifeessentials.jbl_speaker.detail")
				.withStyle(ChatFormatting.AQUA));
		tooltip.add(Component.translatable("tooltip.lifeessentials.jbl_speaker.hint")
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
