package com.hxzhitang.tongdarailway.railway;

import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;

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
     * 从世界坐标计算其所在的区域坐标
     * @param wx 世界坐标X
     * @param wz 世界坐标Z
     * @return 所在区域坐标
     */
    public static RegionPos regionPosFromWorldPos(int wx, int wz) {
        return new RegionPos(Math.floorDiv(wx, CHUNK_GROUP_SIZE*16), Math.floorDiv(wz, CHUNK_GROUP_SIZE*16));
    }
}
