package com.hxzhitang.tongdarailway.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.*;

public class StationTemplate extends ModTemplate {
    public enum StationType {
        NORMAL,
        UNDER_GROUND
    }

    private final List<Exit> exits = new ArrayList<>();

    private final int id;

    private final StationType type;

    private StationTemplate(String path, int heightOffset, int id, StationType type) {
        super(Path.of(path), heightOffset);
        this.id = id;
        this.type = type;

        searchExit();
    }

    public StationTemplate(CompoundTag rootTag, int heightOffset, int id, StationType type) {
        super(rootTag, heightOffset);
        this.id = id;
        this.type = type;

        searchExit();
    }

    @Override
    public boolean isInVoxel(double x, double y, double z) {
        int originalX = (int) Math.floor(x + getWidth() / 2.0);
        int originalY = (int) Math.floor(y) + heightOffset;
        int originalZ = (int) Math.floor(z + getDepth() / 2.0);

        return originalX >= 0 && originalX < getWidth() && originalY >= 0 && originalY < getHeight() && originalZ >= 0 && originalZ < getDepth();
    }

    @Override
    public BlockState getBlockState(double x, double y, double z) {
        int originalX = (int) Math.floor(x + getWidth() / 2.0);
        int originalY = (int) Math.floor(y) + heightOffset;
        int originalZ = (int) Math.floor(z + getDepth() / 2.0);

        var blockState = voxelGrid.getBlockState(originalX, originalY, originalZ);

        if (blockState != null && blockState.is(Blocks.JIGSAW))
            return Blocks.AIR.defaultBlockState();

        return blockState;
    }

    public Set<ChunkPos> getBoundChunks(Vec3 center) {
        Set<ChunkPos> chunks = new HashSet<>();

        // 计算区域的AABB（轴对齐边界框）
        double minX = center.x - getWidth() / 2.0;
        double maxX = center.x + getWidth() / 2.0;
        double minZ = center.z - getDepth() / 2.0;
        double maxZ = center.z + getDepth() / 2.0;

        // 转换为区块坐标（一个区块是16x16x256格）
        int minChunkX = (int) Math.floor(minX / 16.0);
        int maxChunkX = (int) Math.floor(maxX / 16.0);
        int minChunkZ = (int) Math.floor(minZ / 16.0);
        int maxChunkZ = (int) Math.floor(maxZ / 16.0);

        // 遍历所有涉及的区块
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }

        return chunks;
    }

    public List<Exit> getExits() {
        return exits;
    }

    public int getExitCount() {
        return exits.size();
    }

    public StationType getType() {
        return type;
    }

    public int getId() {
        return id;
    }

    private void searchExit() {
        var palette = voxelGrid.getPalette();
        BlockPos off = new BlockPos(
                -(int) Math.floor(getWidth() / 2.0) + 1,
                -heightOffset + 1,
                -(int) Math.floor(getDepth() / 2.0) + 1
        );
        for (int i = 0; i < voxelGrid.getWidth(); i++) {
            for (int j = 0; j < voxelGrid.getHeight(); j++) {
                for (int k = 0; k < voxelGrid.getDepth(); k++) {
                    int index = voxelGrid.getVoxel(i, j, k);
                    if (index != -1 && palette.get(index).is(Blocks.JIGSAW)) {
                        var dir = JigsawBlock.getFrontFacing(palette.get(index));
                        switch (dir) {
                            case Direction.NORTH ->
                                    exits.add(new Exit(new BlockPos(i, j, k).offset(off), new Vec3(0, 0, -1)));
                            case Direction.EAST ->
                                    exits.add(new Exit(new BlockPos(i, j, k).offset(off), new Vec3(1, 0, 0)));
                            case Direction.SOUTH ->
                                    exits.add(new Exit(new BlockPos(i, j, k).offset(off), new Vec3(0, 0, 1)));
                            case Direction.WEST ->
                                    exits.add(new Exit(new BlockPos(i, j, k).offset(off), new Vec3(-1, 0, 0)));
                        }
                    }
                }
            }
        }
    }

    public record Exit(
            BlockPos exitPos,
            Vec3 dir
    ) {
    }
}
