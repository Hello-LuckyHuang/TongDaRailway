package com.hxzhitang.tongdarailway.structure;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.util.MyRandom;
import com.hxzhitang.tongdarailway.util.RandomPool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@EventBusSubscriber
public class ModStructureManager extends SimpleJsonResourceReloadListener {
    private static final String folder = "railway_structure";

    // 4出口车站
    public static final RandomPool<StationTemplate> station4 = new RandomPool<>();
    // 2出口车站
    public static final RandomPool<StationTemplate> station2 = new RandomPool<>();

    // 路面路基
    public static final Map<Integer, RailwayTemplate> ground = new HashMap<>();
    // 隧道
    public static final Map<Integer, RailwayTemplate> tunnel = new HashMap<>();
    // 桥梁
    public static final Map<Integer, RailwayTemplate> bridge = new HashMap<>();

    @SubscribeEvent
    public static void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ModStructureManager());
    }

    public ModStructureManager() {
        super(new Gson(), folder);
    }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profilerFiller) {
        Map<ResourceLocation, JsonElement> jsonData = new HashMap<>();
        resourceManager.listResources(folder, location ->
                location.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                Gson gson = new Gson();
                InputStream resourceStream = resourceManager
                    .getResource(location)
                    .orElseThrow()
                    .open();
                InputStreamReader reader = new InputStreamReader(resourceStream);
                jsonData.put(location, gson.fromJson(reader, JsonElement.class));
            } catch (IOException e) {
                Tongdarailway.LOGGER.error("Error loading structure json file: ", e);
            }
        });
        return jsonData;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resourceList, ResourceManager resourceManagerIn, ProfilerFiller profilerIn) {
        resourceList.forEach((location, json) -> {
            JsonObject jsonobject = json.getAsJsonObject();
            String temType = GsonHelper.getAsString(jsonobject, "class");
            String type = GsonHelper.getAsString(jsonobject, "type");
            String nbt = GsonHelper.getAsString(jsonobject, "template");
            int heightOffset = GsonHelper.getAsInt(jsonobject, "height_offset");
            List<JsonElement> tags = GsonHelper.getAsJsonArray(jsonobject, "tags").asList();
            ResourceLocation nbtLocation = ResourceLocation.fromNamespaceAndPath(
                    nbt.split(":")[0],
                    "structure/" + nbt.split(":")[1] + ".nbt"
            );
            CompoundTag rootTag = null;
            try {
                InputStream resourceStream = resourceManagerIn
                        .getResource(nbtLocation)
                        .orElseThrow()
                        .open();
                try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
                        new GZIPInputStream(resourceStream)))) {
                    rootTag = NbtIo.read(stream, NbtAccounter.create(0x20000000L));
                } catch (Exception e) {
                    Tongdarailway.LOGGER.error("Load Structure nbt file Err: {}", nbtLocation.getPath(), e);
                }

                if (rootTag != null) {
                    int id = location.getPath().hashCode();
                    // 获得标签数组(若为空,则默认添加"default"标签)
                    int length = tags.isEmpty() ? 2 : tags.size()+1;
                    String[] tagArray = new String[length];
                    tagArray[0] = type;
                    if (tags.isEmpty())
                        tagArray[1] = "default";
                    else
                        for (int i = 0; i < tags.size(); i++) {
                            tagArray[i+1] = tags.get(i).getAsString();
                        }
                    if (temType.equals("station")) {
                        StationTemplate template = new StationTemplate(rootTag, heightOffset, id, StationTemplate.StationType.NORMAL);
                        if (template.getExitCount() == 4)
                            station4.add(template, id, tagArray);
                        else if (template.getExitCount() == 2)
                            station2.add(template, id, tagArray);
                        else
                            Tongdarailway.LOGGER.error("Invalid StationTemplate: {}", location.getPath());
                    } else if (temType.equals("roadbed")) {
                        RailwayTemplate template = new RailwayTemplate(rootTag, heightOffset);
                        switch (type) {
                            case "ground" -> ground.put(id, template);
                            case "tunnel" -> tunnel.put(id, template);
                            case "bridge" -> bridge.put(id, template);
                        }
                    }
                }
            } catch (Exception e) {
                Tongdarailway.LOGGER.error("Load Structure Data Err: {}, nbt data: {}", location.getPath(), nbtLocation.getPath(), e);
            }
        });
    }

    // 随机获取用于生成的结构模板
    public static StationTemplate getRandomNormalStation(long seed, int exitNum, String... tags) {
        String type = "normal";
        RandomPool<StationTemplate> pool = exitNum == 2 ? station2 : station4;
        return pool.get(75_457 + seed*10000, type, tags);
    }

    public static StationTemplate getRandomUnderGroundStation(long seed, int exitNum, String... tags) {
        String type = "underground";
        RandomPool<StationTemplate> pool = exitNum == 2 ? station2 : station4;
        return pool.get(75_457 + seed*10000, type, tags);
    }

    public static RailwayTemplate getRandomGround(long seed) {
        if (ground.isEmpty()) {
            return null;
        }

        return MyRandom.getRandomValueFromMap(ground, 84_270 + seed*10000);
    }

    public static RailwayTemplate getRandomTunnel(long seed) {
        if (tunnel.isEmpty()) {
            return null;
        }

        return MyRandom.getRandomValueFromMap(tunnel, 71_1553 + seed*10000);
    }

    public static RailwayTemplate getRandomBridge(long seed) {
        if (bridge.isEmpty()) {
            return null;
        }

        return MyRandom.getRandomValueFromMap(bridge, 90_318 + seed*10000);
    }
}
