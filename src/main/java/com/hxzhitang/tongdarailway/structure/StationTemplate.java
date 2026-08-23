package com.hxzhitang.tongdarailway.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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

    public final StationType type;

    private StationTemplate(String path, int heightOffset, int id, StationType type) {
        super(Path.of(path), heightOffset);
        this.id = id;
        this.type = type;

        searchExit();
    }

    public StationTemplate(CompoundTag rootTag, int heightOffset, int id, StationType type) {
        this(rootTag, heightOffset, id, type, Rotation.NONE);
    }

    public StationTemplate(CompoundTag rootTag, int heightOffset, int id, StationType type, Rotation rotation) {
        super(rootTag, heightOffset, rotation);
        this.id = id;
        this.type = type;

        searchExit();
    }

    @Override
    public boolean isInVoxel(double x, double y, double z) {
        int originalX = (int) (x + Math.floor(getWidth() / 2.0));
        int originalY = (int) Math.floor(y) + heightOffset;
        int originalZ = (int) (z + Math.floor(getDepth() / 2.0));

        return originalX >= 0 && originalX < getWidth() && originalY >= 0 && originalY < getHeight() && originalZ >= 0 && originalZ < getDepth();
    }

    @Override
    public BlockState getBlockState(double x, double y, double z) {
        int originalX = (int) (x + Math.floor(getWidth() / 2.0));
        int originalY = (int) Math.floor(y) + heightOffset;
        int originalZ = (int) (z + Math.floor(getDepth() / 2.0));

        var blockState = voxelGrid.getBlockState(originalX, originalY, originalZ);

        if (blockState != null && blockState.is(Blocks.JIGSAW))
            return Blocks.AIR.defaultBlockState();

        return blockState;
    }

    public CompoundTag getBlockEntityTag(double x, double y, double z) {
        int originalX = (int) (x + Math.floor(getWidth() / 2.0));
        int originalY = (int) Math.floor(y) + heightOffset;
        int originalZ = (int) (z + Math.floor(getDepth() / 2.0));

        return voxelGrid.getBlockEntityTag(originalX, originalY, originalZ);
    }

    public CompoundTag getBlockEntityTag(Vec3 pos) {
        return getBlockEntityTag(pos.x(), pos.y(), pos.z());
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

    // 提取出口坐标
    public List<Exit> getExitsPos(BlockPos placePos) {
        List<StationTemplate.Exit> exits = new ArrayList<>();
        for (StationTemplate.Exit exit : getExits()) {
            BlockPos offset = exit.exitPos();
            exits.add(new StationTemplate.Exit(placePos.offset(offset), exit.dir()));
        }

        return exits;
    }

    public StationType getType() {
        return type;
    }

    public int getId() {
        return id;
    }

    /**
     * Returns the station's exact template bounds in world coordinates for vanilla terrain adaptation.
     */
    public BoundingBox getBeardifierBox(BlockPos placePos) {
        int minX = placePos.getX() + 1 - (int) Math.floor(getWidth() / 2.0);
        int minY = placePos.getY() + 1 - heightOffset;
        int minZ = placePos.getZ() + 1 - (int) Math.floor(getDepth() / 2.0);
        return new BoundingBox(
                minX,
                minY,
                minZ,
                minX + getWidth() - 1,
                minY + getHeight() - 1,
                minZ + getDepth() - 1
        );
    }

    /**
     * Moves Beardifier's effective ground level one block above the station placement height so the
     * generated terrain's top solid layer meets the station floor.
     */
    public int getBeardifierGroundLevelDelta() {
        return heightOffset;
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
        // 推远
        public int[] pushAway(Vec3 target) {
            Vec3 ep = exitPos.getCenter().multiply(1,0,1);
            Vec3 t = target.multiply(1,0,1);
            Vec3 a = t.subtract(ep).normalize();

            Vec3 vert = new Vec3(0, 1, 0);
            Vec3 vb = dir.cross(vert).normalize();

            int d = (int) (a.dot(vb) * 45);
            d = Math.abs(d) < 7 ? 0 : d;
            Vec3 end = dir.scale(65).add(vb.scale(d));

            Vec3 pos = Vec3.atCenterOf(exitPos);
            Vec3 start = pos.add(end);

            return new int[]{(int) start.x, (int) start.z, exitPos.getY()};
        }
    }
}
