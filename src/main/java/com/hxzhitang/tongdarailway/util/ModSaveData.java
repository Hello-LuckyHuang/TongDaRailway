package com.hxzhitang.tongdarailway.util;

import com.hxzhitang.tongdarailway.railway.RailwayMap;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModSaveData extends SavedData {
    public static final String NAME = "tongdarailway_mod_railway_data";
    public final Map<RegionPos, RailwayMap> regionRailways = new ConcurrentHashMap<>();

    public void putRailwayMap(RegionPos regionPos, RailwayMap railwayMap) {
        regionRailways.put(regionPos, railwayMap);
        setDirty();
    }

    public RailwayMap getRailwayMap(RegionPos regionPos) {
        setDirty();
        return regionRailways.get(regionPos);
    }

    public static ModSaveData create() {
        return new ModSaveData();
    }

    @Override
    public CompoundTag save(CompoundTag pCompoundTag, HolderLookup.Provider provider) {
        ListTag listTag = new ListTag();
        regionRailways.forEach((pos, railwayMap) -> {
            CompoundTag compoundTag = new CompoundTag();
            compoundTag.put("RegionPos", pos.toNBT());
            compoundTag.put("RailwayMap", railwayMap.toNBT());
            listTag.add(compoundTag);
        });
        pCompoundTag.put("RailwayData", listTag);
        return pCompoundTag;
    }

    public static ModSaveData load(CompoundTag nbt, HolderLookup.Provider provider) {
        ModSaveData data = ModSaveData.create();
        ListTag listNBT = (ListTag) nbt.get("RailwayData");
        if (listNBT != null) {
            for (Tag value : listNBT) {
                CompoundTag tag = (CompoundTag) value;
                RegionPos regionPos = RegionPos.fromNBT((ListTag) tag.get("RegionPos"));
                CompoundTag dataTag = (CompoundTag) tag.get("RailwayMap");
                RailwayMap railwayMap;
                if (dataTag != null) {
                    railwayMap = RailwayMap.fromNBT(dataTag);
                    data.regionRailways.put(regionPos, railwayMap);
                }
            }
        }

        return data;
    }

    public static ModSaveData get(Level worldIn) {
        if (!(worldIn instanceof ServerLevel)) {
            throw new RuntimeException("Attempted to get the data from a client world. This is wrong.");
        }
        ServerLevel world = worldIn.getServer().getLevel(ServerLevel.OVERWORLD);
        DimensionDataStorage dataStorage = world.getDataStorage();
        return dataStorage.computeIfAbsent(new Factory<ModSaveData>(ModSaveData::create, ModSaveData::load, DataFixTypes.LEVEL), ModSaveData.NAME);
    }
}
