package com.hxzhitang.tongdarailway.blocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BadgeBlock extends FaceAttachedHorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<BadgeBlock> CODEC = simpleCodec(BadgeBlock::new);

    private static final VoxelShape FLOOR_NORTH_SOUTH = Block.box(0.0, 0.0, 3.0, 16.0, 3.0, 13.0);
    private static final VoxelShape FLOOR_EAST_WEST = Block.box(3.0, 0.0, 0.0, 13.0, 3.0, 16.0);
    private static final VoxelShape CEILING_NORTH_SOUTH = Block.box(0.0, 13.0, 3.0, 16.0, 16.0, 13.0);
    private static final VoxelShape CEILING_EAST_WEST = Block.box(3.0, 13.0, 0.0, 13.0, 16.0, 16.0);
    private static final VoxelShape NORTH = Block.box(0.0, 3.0, 13.0, 16.0, 13.0, 16.0);
    private static final VoxelShape SOUTH = Block.box(0.0, 3.0, 0.0, 16.0, 13.0, 3.0);
    private static final VoxelShape WEST = Block.box(13.0, 3.0, 0.0, 16.0, 13.0, 16.0);
    private static final VoxelShape EAST = Block.box(0.0, 3.0, 0.0, 3.0, 13.0, 16.0);

    public BadgeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACE, AttachFace.WALL)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return switch (state.getValue(FACE)) {
            case FLOOR -> facing.getAxis() == Direction.Axis.X ? FLOOR_EAST_WEST : FLOOR_NORTH_SOUTH;
            case CEILING -> facing.getAxis() == Direction.Axis.X ? CEILING_EAST_WEST : CEILING_NORTH_SOUTH;
            case WALL -> switch (facing) {
                case NORTH -> NORTH;
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                case EAST -> EAST;
                default -> throw new IllegalStateException("Unexpected badge facing: " + facing);
            };
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BadgeBlockEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
    }
}
