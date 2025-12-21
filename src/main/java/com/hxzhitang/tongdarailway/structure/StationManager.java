package com.hxzhitang.tongdarailway.structure;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.util.MyRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

@Mod.EventBusSubscriber
public class StationManager {
    private static final Map<ResourceLocation, CompoundTag> LOADED_STRUCTURES = new HashMap<>();

    // 普通车站
    public static final Map<Integer, StationStructure> normalStation = new HashMap<>();
    // 地下车站
    public static final Map<Integer, StationStructure> undergroundStation = new HashMap<>();


    @SubscribeEvent
    public static void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new NBTResourceReloadListener());
    }

    public static StationStructure getRandomNormalStation(long seed) {
        if (normalStation.isEmpty()) {
            return null;
        }

        return MyRandom.getRandomValueFromMap(normalStation, 84_269 + seed*10000);
    }

    public static StationStructure getRandomUnderGroundStation(long seed) {
        if (undergroundStation.isEmpty()) {
            return null;
        }

        return MyRandom.getRandomValueFromMap(undergroundStation, 71_1551 + seed*10000);
    }

    private static class NBTResourceReloadListener extends SimplePreparableReloadListener<Map<ResourceLocation, CompoundTag>> {
        @Override
        protected Map<ResourceLocation, CompoundTag> prepare(ResourceManager resourceManager, ProfilerFiller profilerFiller) {
            Map<ResourceLocation, CompoundTag> nbtData = new HashMap<>();
            resourceManager.listResources("structure/station", location ->
                    location.getPath().endsWith(".nbt")
            ).forEach((location, resource) -> {
                try {
                    InputStream resourceStream = resourceManager
                            .getResource(location)
                            .orElseThrow()
                            .open();
                    try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
                            new GZIPInputStream(resourceStream)))) {
                        CompoundTag rootTag = NbtIo.read(stream, new NbtAccounter(0x20000000L));
                        nbtData.put(location, rootTag);
                    } catch (Exception e) {
                        Tongdarailway.LOGGER.error(e.getMessage());
                    }
                } catch (Exception e) {
                    Tongdarailway.LOGGER.error(e.getMessage());
                }
            });

            return nbtData;
        }

        @Override
        protected void apply(Map<ResourceLocation, CompoundTag> prepared, ResourceManager resourceManager, ProfilerFiller profilerFiller) {
            LOADED_STRUCTURES.clear();
            LOADED_STRUCTURES.putAll(prepared);
            init();
            Tongdarailway.LOGGER.info("Loaded {} NBT structures from resources", LOADED_STRUCTURES.size());
        }

        private void init() {
            LOADED_STRUCTURES.forEach((location, compoundTag) -> {
                Tongdarailway.LOGGER.info("Loading station template: {}", location.getPath());
                int id = location.getPath().hashCode();
                String[] subString = location.getPath().split("structure/station/");
                if (subString.length == 2) {
                    switch (subString[1].split("/")[0]) {
                        case "normal":
                            StationStructure normalStation = new StationStructure(compoundTag, id, StationStructure.StationType.NORMAL);
                            if (normalStation.getExitCount() == 4) {
                                StationManager.normalStation.put(id, normalStation);
                            } else {
                                Tongdarailway.LOGGER.warn("Invalid station structure. Exits count must 4: {} (exit count: {})", location.getPath(), normalStation.getExitCount());
                            }
                            break;
                        case "underground":
                            StationStructure undergroundStation = new StationStructure(compoundTag, id, StationStructure.StationType.UNDER_GROUND);
                            if (undergroundStation.getExitCount() == 4) {
                                StationManager.undergroundStation.put(id, undergroundStation);
                            } else {
                                Tongdarailway.LOGGER.warn("Invalid station structure. Exits count must 4: {} (exit count: {})", location.getPath(), undergroundStation.getExitCount());
                            }
                            break;
                    }
                }
            });
        }
    }
}
