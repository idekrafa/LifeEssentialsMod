package com.lifeessentials.block;

import com.lifeessentials.ModBlocks;
import com.lifeessentials.client.SpeakerClientOpener;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** A portable Bluetooth speaker: right-click for the deck, redstone toggles play. */
public class JblSpeakerBlock extends BaseEntityBlock {
	public static final MapCodec<JblSpeakerBlock> CODEC = simpleCodec(JblSpeakerBlock::new);

	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty PLAYING = BooleanProperty.create("playing");

	/** Boombox body plus the carry handle, long axis across the facing. */
	private static final VoxelShape SHAPE_NS = Block.box(0.25, 0.0, 3.5, 15.75, 15.5, 12.5);
	private static final VoxelShape SHAPE_EW = Block.box(3.5, 0.0, 0.25, 12.5, 15.5, 15.75);

	public JblSpeakerBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
				.setValue(FACING, Direction.NORTH)
				.setValue(PLAYING, false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, PLAYING);
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState()
				.setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	// ------------------------------------------------------------ block entity

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SpeakerBlockEntity(pos, state);
	}

	@Override
	@Nullable
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
			BlockEntityType<T> type) {
		if (level.isClientSide) return null;
		return createTickerHelper(type, ModBlocks.SPEAKER_BE.get(), SpeakerBlockEntity::serverTick);
	}

	// ------------------------------------------------------------ interaction

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hit) {
		if (level.isClientSide) {
			SpeakerClientOpener.open(pos);
			return InteractionResult.SUCCESS;
		}
		if (player instanceof ServerPlayer serverPlayer
				&& level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
			ServerSpeakerManager.sendLibrary(serverPlayer);
			ServerSpeakerManager.syncToPlayer(serverPlayer, speaker);
		}
		return InteractionResult.CONSUME;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
			ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide && placer instanceof ServerPlayer player) {
			ServerSpeakerManager.sendLibrary(player);
		}
	}

	/** A rising redstone edge works like tapping play/pause on the speaker itself. */
	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
			BlockPos neighbourPos, boolean moving) {
		if (level.isClientSide) return;
		if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
			speaker.onRedstoneChanged(level.hasNeighborSignal(pos));
		}
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
			boolean moving) {
		if (!state.is(newState.getBlock()) && !level.isClientSide) {
			ServerSpeakerManager.onSpeakerRemoved(level, pos);
		}
		super.onRemove(state, level, pos, newState, moving);
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return state.getValue(PLAYING) ? 15 : 0;
	}
}
