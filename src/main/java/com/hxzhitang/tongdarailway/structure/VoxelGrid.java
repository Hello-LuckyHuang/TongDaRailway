package com.hxzhitang.tongdarailway.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class VoxelGrid {
    private final List<BlockState> palette;
    private final int[][][] voxelGrid;
    private final BlockPos size;

    public int offsetX = 0;
    public int offsetY = 0;
    public int offsetZ = 0;

    public VoxelGrid(List<BlockState> palette, int[][][] voxelGrid, BlockPos size) {
        this.palette = palette;
        this.voxelGrid = voxelGrid;
        this.size = size;
    }

    public VoxelGrid(List<BlockState> palette, BlockPos size) {
        this.palette = palette;
        this.voxelGrid = new int[size.getX()][size.getY()][size.getZ()];
        this.size = size;
    }

    // Getters
    public List<BlockState> getPalette() {
        return palette;
    }

    public int[][][] getVoxelGrid() {
        return voxelGrid;
    }

    public BlockPos getSize() {
        return size;
    }

    public int getWidth() {
        return size.getX();
    }

    public int getHeight() {
        return size.getY();
    }

    public int getDepth() {
        return size.getZ();
    }

    public ListTag getBlocks() {
        ListTag blocks = new ListTag();
        for (int i = 0; i < size.getX(); i++) {
            for (int j = 0; j < size.getY(); j++) {
                for (int k = 0; k < size.getZ(); k++) {
                    if (voxelGrid[i][j][k] != 0) {
                        CompoundTag tag = new CompoundTag();
                        ListTag pos = new ListTag();
                        pos.add(IntTag.valueOf(i - offsetX));
                        pos.add(IntTag.valueOf(j - offsetY));
                        pos.add(IntTag.valueOf(k - offsetZ));
                        tag.put("pos", pos);
                        tag.putInt("state", voxelGrid[i][j][k] - 1);
                        blocks.add(tag);
                    }
                }
            }
        }

        return blocks;
    }

    public ListTag getPaletteTag() {
        ListTag paletteTag = new ListTag();

        if (!palette.isEmpty()) {
            palette.removeFirst();
            for (BlockState block : palette) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Name", BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
                paletteTag.add(tag);
            }
        }

        return paletteTag;
    }

    public boolean isInVoxel(int x, int y, int z) {
         return x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ();
    }

    public BlockState getBlockState(int x, int y, int z) {
        if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
            int paletteIndex = voxelGrid[x][y][z];
            if (paletteIndex > 0 && paletteIndex < palette.size()) {
                var blockState = palette.get(paletteIndex);
                if (blockState.is(Blocks.STRUCTURE_VOID))
                    return null; // 结构空位
                if (blockState.is(Blocks.STRUCTURE_BLOCK))
                    return Blocks.AIR.defaultBlockState(); // 防止出现结构方块

                return blockState;
            }
        }
        return null; // 空气或无方块
    }

    public void setVoxel(int x, int y, int z, int value) {
        if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
            voxelGrid[x][y][z] = value;
        }
    }

    public int getVoxel(int x, int y, int z) {
        if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
            return voxelGrid[x][y][z];
        }
        return -1; // 默认空体素
    }
}
