package com.hxzhitang.tongdarailway.railway;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.planner.RoutePlanner;
import com.hxzhitang.tongdarailway.railway.planner.StationPlanner;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.CurveRoute;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

import java.util.function.Consumer;

public class RailwayMap {
    public static final int samplingNum = 2; // 每个区块的采样数

    public final RegionPos regionPos;

    //********每个区域的数据*********
    // 路线
    public final Map<ChunkPos, Set<CurveRoute>> routeMap = new ConcurrentHashMap<>();
    // 车站
    public final List<StationPlanner.StationGenInfo> stations = new ArrayList<>();
    // 铁轨
    public final Map<ChunkPos, List<TrackPutInfo>> trackMap = new ConcurrentHashMap<>();
    //********每个区域的数据*********

    /** 路线规划管线，按照枚举声明顺序执行各个阶段。 */
    public enum PlanningStep {
        // 站点生成
        STATION_GENERATION(StationPlanner::generateStations),
        // 站点连接
        STATION_CONNECTION(StationPlanner::connectStations),
        // 寻路
        PATHFINDING(RoutePlanner::findPaths),
        // 高度调整
        HEIGHT_ADJUSTMENT(RoutePlanner::adjustHeights),
        // 轨道曲线生成
        TRACK_CURVE_GENERATION(RoutePlanner::generateTrackCurves);

        private final Consumer<RoutePlanner.PlanningContext> operation;

        PlanningStep(Consumer<RoutePlanner.PlanningContext> operation) {
            this.operation = operation;
        }

        public void execute(RoutePlanner.PlanningContext context) {
            operation.accept(context);
        }
    }

    public RailwayMap(RegionPos regionPos) {
        this.regionPos = regionPos;
    }

    // 规划铁路路线方法
    public void startPlanningRoutes(WorldGenRegion level, RailwayBuilder builder) {
        builder.checkPlanningCancelled();
        try {
            var context = new RoutePlanner.PlanningContext(this, level, builder);
            for (PlanningStep step : PlanningStep.values()) {
                builder.checkPlanningCancelled();
                step.execute(context);
            }
        } catch (CancellationException exception) {
            throw exception;
        } catch (Exception e) {
            Tongdarailway.LOGGER.error("Err in gen route: ", e);
            throw new IllegalStateException("Railway route planning failed", e);
        }
    }

    public CompoundTag toNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("RegionPos", regionPos.toNBT());

        // 保存车站
        ListTag stationTag = new ListTag();
        stations.forEach(station -> stationTag.add(station.toNBT()));
        nbt.put("Stations", stationTag);

        // 保存路线
        List<CurveRoute> palette = new ArrayList<>();
        ListTag routeMapTag = new ListTag();
        routeMap.forEach((pos, routes) -> {
            CompoundTag chunkNbt = new CompoundTag();
            chunkNbt.putInt("ChunkPosX", pos.x);
            chunkNbt.putInt("ChunkPosZ", pos.z);
            ListTag routesTag = new ListTag();
            for (CurveRoute route : routes) {
                int index;
                if (palette.contains(route)) {
                    index = palette.indexOf(route);
                } else {
                    palette.add(route);
                    index = palette.size() - 1;
                }
                routesTag.add(IntTag.valueOf(index));
            }
            chunkNbt.put("Routes", routesTag);
            routeMapTag.add(chunkNbt);
        });
        ListTag paletteTag = new ListTag();
        for (CurveRoute route : palette) {
            paletteTag.add(route.toNBT());
        }
        nbt.put("RouteMap", routeMapTag);
        nbt.put("RoutePalette", paletteTag);

        // 保存铁轨
        ListTag trackMapTag = new ListTag();
        trackMap.forEach((pos, tracks) -> {
            CompoundTag chunkNbt = new CompoundTag();
            chunkNbt.putInt("ChunkPosX", pos.x);
            chunkNbt.putInt("ChunkPosZ", pos.z);
            ListTag tracksTag = new ListTag();
            for (TrackPutInfo track : tracks) {
                tracksTag.add(track.toNBT());
            }
            chunkNbt.put("Tracks", tracksTag);
            trackMapTag.add(chunkNbt);
        });
        nbt.put("TrackMap", trackMapTag);

        return nbt;
    }

    public static RailwayMap fromNBT(CompoundTag nbt) {
        RegionPos regionPos = RegionPos.fromNBT((ListTag) nbt.get("RegionPos"));
        RailwayMap railwayMap = new RailwayMap(regionPos);

        // 读取车站
        ListTag stationTag = (ListTag) nbt.get("Stations");
        if (stationTag != null) {
            for (net.minecraft.nbt.Tag tag : stationTag) {
                StationPlanner.StationGenInfo station = StationPlanner.StationGenInfo.fromNBT((CompoundTag) tag);
                railwayMap.stations.add(station);
            }
        }

        // 读取路径
        ListTag routeTag = (ListTag) nbt.get("RouteMap");
        ListTag paletteTag = (ListTag) nbt.get("RoutePalette");
        List<CurveRoute> palette = new ArrayList<>();
        if (paletteTag != null && routeTag != null) {
            for (Tag tag : paletteTag) {
                palette.add(CurveRoute.fromNBT((ListTag) tag));
            }
            for (net.minecraft.nbt.Tag tag : routeTag) {
                CompoundTag chunkNbt = (CompoundTag) tag;
                ChunkPos chunkPos = new ChunkPos(chunkNbt.getInt("ChunkPosX"), chunkNbt.getInt("ChunkPosZ"));
                if (chunkNbt.contains("Routes")) {
                    Set<CurveRoute> routes = new HashSet<>();
                    for (net.minecraft.nbt.Tag tag1 : chunkNbt.getList("Routes", Tag.TAG_INT)) {
                        int index = ((IntTag) tag1).getAsInt();
                        CurveRoute route = palette.get(index);
                        routes.add(route);
                    }
                    railwayMap.routeMap.put(chunkPos, routes);
                }
            }
        }

        // 读取铁轨
        ListTag trackTag = (ListTag) nbt.get("TrackMap");
        if (trackTag != null) {
            for (net.minecraft.nbt.Tag tag : trackTag) {
                CompoundTag chunkNbt = (CompoundTag) tag;
                ChunkPos chunkPos = new ChunkPos(chunkNbt.getInt("ChunkPosX"), chunkNbt.getInt("ChunkPosZ"));
                if (chunkNbt.contains("Tracks")) {
                    List<TrackPutInfo> tracks = new ArrayList<>();
                    for (net.minecraft.nbt.Tag tag1 : chunkNbt.getList("Tracks", Tag.TAG_COMPOUND)) {
                        TrackPutInfo track = TrackPutInfo.fromNBT((CompoundTag) tag1);
                        tracks.add(track);
                    }
                    railwayMap.trackMap.put(chunkPos, tracks);
                }
            }
        }

        return railwayMap;
    }
}