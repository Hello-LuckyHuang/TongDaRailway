package com.hxzhitang.tongdarailway.structure;

import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

public class RailwayTemplate extends ModTemplate {
    public RailwayTemplate(Path path, int heightOffset) {
        super(path, heightOffset);
    }

    public RailwayTemplate(CompoundTag nbt, int heightOffset) {
        super(nbt, heightOffset);
    }

    @Override
    public boolean isInVoxel(double x, double y, double z) {
        int originalX = (int) Math.floor(x) % getWidth();

        int originalY = (int) Math.round(y + heightOffset + 1);  // 从路面高度计y坐标
        int originalZ = (int) Math.floor(z + getDepth() / 2.0);

        return voxelGrid.isInVoxel(originalX, originalY, originalZ);
    }

    // 坐标系原点在z方向中心方块的方块坐标处
    @Override
    public BlockState getBlockState(double x, double y, double z) {
        // 映射回原始体素坐标
        // 原始X坐标由曲线参数决定
        int originalX = (int) Math.floor(x) % getWidth();

        // 原始Y和Z坐标由局部坐标决定（考虑网格中心）
        int originalY = (int) Math.round(y + heightOffset + 1);  // 从路面高度计y坐标
        int originalZ = (int) Math.floor(z + getDepth() / 2.0);

        // 重复最底层方块
        if (originalY < 0)
            originalY = 0;

        return voxelGrid.getBlockState(originalX, originalY, originalZ);
    }
}

