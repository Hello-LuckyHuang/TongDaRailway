package com.hxzhitang.tongdarailway.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BadgeBlockEntity extends BlockEntity {
    public BadgeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TONGDARAILWAY_BADGE.get(), pos, state);
    }
}
