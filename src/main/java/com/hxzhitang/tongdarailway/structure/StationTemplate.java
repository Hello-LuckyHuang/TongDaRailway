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

    public final StationType type;

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

    public Set<ChunkPos> getTrayBoundChunks(Vec3 center) {
        Set<ChunkPos> chunks = new HashSet<>();

        int trayWidth = getWidth() + 20 + 10;
        int trayDepth = getDepth() + 20 + 10;

        // 计算区域的AABB（轴对齐边界框）
        double minX = center.x - trayWidth / 2.0;
        double maxX = center.x + trayWidth / 2.0;
        double minZ = center.z - trayDepth / 2.0;
        double maxZ = center.z + trayDepth / 2.0;

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

    /**
     * 计算点 P 到平面四边形 托盘 的最短距离
     * 计算几何这一块
     */
    public double dis2Tray(Vec3 center, Vec3 p) {
        int trayWidth = getWidth() + 20;
        int trayDepth = getDepth() + 20;

        double minX = center.x - trayWidth / 2.0;
        double maxX = center.x + trayWidth / 2.0;
        double minZ = center.z - trayDepth / 2.0;
        double maxZ = center.z + trayDepth / 2.0;

        double y = center.y;

        Vec3 v0 = new Vec3(minX, y, minZ), v1 = new Vec3(minX, y, maxZ), v2 = new Vec3(maxX, y, maxZ), v3 = new Vec3(maxX, y, minZ);

        // 1. 计算法向量
        Vec3 e10 = v1.subtract(v0);
        Vec3 e20 = v2.subtract(v0);
        Vec3 n = e10.cross(e20);
        double areaSq = n.dot(n);

        // 如果四边形退化
        if (areaSq < 1e-12) return distPointToSegment(p, v0, v2); // 简化处理

        // 2. 计算点到平面的垂直距离
        double h = p.subtract(v0).dot(n) / Math.sqrt(areaSq);
        Vec3 pProj = p.subtract(n.scale(h / Math.sqrt(areaSq)));

        // 3. 判断投影点是否在四边形内部 (仅适用于凸四边形)
        Vec3[] verts = {v0, v1, v2, v3};
        boolean inside = true;
        for (int i = 0; i < 4; i++) {
            Vec3 edge = verts[(i + 1) % 4].subtract(verts[i]);
            Vec3 vp = pProj.subtract(verts[i]);
            // 如果叉积方向与法线相反，说明在外部
            if (edge.cross(vp).dot(n) < 0) {
                inside = false;
                break;
            }
        }

        if (inside) {
            return Math.abs(h);
        }

        // 4. 如果在外部，计算到 4 条边的最小距离
        double d1 = distPointToSegment(p, v0, v1);
        double d2 = distPointToSegment(p, v1, v2);
        double d3 = distPointToSegment(p, v2, v3);
        double d4 = distPointToSegment(p, v3, v0);

        return Math.min(Math.min(d1, d2), Math.min(d3, d4));
    }

    /**
     * 点到线段的最短距离
     */
    public static double distPointToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 v = b.subtract(a);
        Vec3 w = p.subtract(a);
        double t = w.dot(v) / v.dot(v);
        t = Math.max(0, Math.min(1, t)); // 限制在 [0, 1] 范围内
        Vec3 closest = a.add(v.scale(t));
        return p.subtract(closest).length();
    }

    public record Exit(
            BlockPos exitPos,
            Vec3 dir
    ) {
    }
}
