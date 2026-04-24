package com.hxzhitang.tongdarailway.railway;

import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;

public record RegionPos(int x, int z) {
    public ListTag toNBT() {
        ListTag listTag = new ListTag();
        listTag.add(IntTag.valueOf(x));
        listTag.add(IntTag.valueOf(z));
        return listTag;
    }

    public static RegionPos fromNBT(ListTag listTag) {
        return new RegionPos(listTag.getInt(0), listTag.getInt(1));
    }


    /**
     * 获取区域内的区块的世界区块坐标
     * @param chunkX 区域内的区块坐标X
     * @param chunkZ 区域内的区块坐标Z
     * @return
     */
    public ChunkPos getChunkPos(int chunkX, int chunkZ) {
        return new ChunkPos(x * CHUNK_GROUP_SIZE + chunkX, z * CHUNK_GROUP_SIZE + chunkZ);
    }

    /**
     * 获取基坐标
     * @return 区域基坐标(世界坐标)
     */
    public Vec2 getBasePos() {
        return new Vec2(x * CHUNK_GROUP_SIZE * 16, z * CHUNK_GROUP_SIZE * 16);
    }

    /**
     * 获取区域边长
     * @return 边长 格
     */
    public int getLength() {
        return CHUNK_GROUP_SIZE * 16;
    }

    /**
     * 从区块世界坐标计算其所在的区域坐标
     * @param chunkPos 区块世界坐标
     * @return 所在区域坐标
     */
    public static RegionPos regionPosFromChunkPos(ChunkPos chunkPos) {
        return new RegionPos(Math.floorDiv(chunkPos.x, CHUNK_GROUP_SIZE), Math.floorDiv(chunkPos.z, CHUNK_GROUP_SIZE));
    }

    /**
     * 从世界坐标计算其所在的区域坐标
     * @param wx 世界坐标X
     * @param wz 世界坐标Z
     * @return 所在区域坐标
     */
    public static RegionPos regionPosFromWorldPos(int wx, int wz) {
        return new RegionPos(Math.floorDiv(wx, CHUNK_GROUP_SIZE*16), Math.floorDiv(wz, CHUNK_GROUP_SIZE*16));
    }
}
