package com.hxzhitang.tongdarailway.util;

import com.hxzhitang.tongdarailway.railway.RegionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;

public class MyMth {
    /**
     * 从区域中的区块坐标计算区块的世界坐标
     * @param regionPos 大区域坐标
     * @param chunkIndexX 区块在区域内的坐标
     * @return 区块世界坐标
     */
    public static int chunkPosXFromRegionPos(RegionPos regionPos, int chunkIndexX) {
        return regionPos.x() * CHUNK_GROUP_SIZE + chunkIndexX;
    }

    /**
     * 从区域中的区块坐标计算区块的世界坐标
     * @param regionPos 大区域坐标
     * @param chunkIndexZ 区块在区域内的坐标
     * @return 区块世界坐标
     */
    public static int chunkPosZFromRegionPos(RegionPos regionPos, int chunkIndexZ) {
        return regionPos.z() * CHUNK_GROUP_SIZE + chunkIndexZ;
    }

    /**
     * 从区块世界坐标计算其所在的区域坐标
     * @param chunkPos 区块世界坐标
     * @return 所在区域坐标
     */
    public static RegionPos regionPosFromChunkPos(ChunkPos chunkPos) {
        return new RegionPos(Math.floorDiv(chunkPos.x, CHUNK_GROUP_SIZE), Math.floorDiv(chunkPos.z, CHUNK_GROUP_SIZE));
    }

    public static ChunkPos getChunkPos(int x, int z) {
        return new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    public static Vec3 inRegionPos2WorldPos(RegionPos regionPos, Vec3 vec3) {
        return vec3.add(new Vec3(regionPos.x()*CHUNK_GROUP_SIZE*16, 0, regionPos.z()*CHUNK_GROUP_SIZE*16));
    }

    public static int getSign(double number) {
        return number > 0 ? 1 : (number < 0 ? -1 : 0);
    }

}
