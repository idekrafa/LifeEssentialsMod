package com.lifeessentials;

import com.lifeessentials.block.JblSpeakerBlock;
import com.lifeessentials.block.SpeakerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModBlocks {
	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LifeEssentials.MOD_ID);
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
			DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LifeEssentials.MOD_ID);

	public static final DeferredBlock<Block> JBL_SPEAKER = BLOCKS.register("jbl_speaker",
			() -> new JblSpeakerBlock(BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_BLACK)
					.strength(1.6F)
					.sound(SoundType.METAL)
					.noOcclusion()));

	public static final Supplier<BlockEntityType<SpeakerBlockEntity>> SPEAKER_BE =
			BLOCK_ENTITIES.register("jbl_speaker", () -> BlockEntityType.Builder
					.of(SpeakerBlockEntity::new, JBL_SPEAKER.get())
					.build(null));

	private ModBlocks() {
	}
}
