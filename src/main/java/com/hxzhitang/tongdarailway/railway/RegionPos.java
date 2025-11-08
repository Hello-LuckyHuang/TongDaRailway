package com.hxzhitang.tongdarailway.railway;

import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;

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
}
