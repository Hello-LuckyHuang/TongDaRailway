package com.hxzhitang.tongdarailway.railway;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.planner.RoutePlanner;
import com.hxzhitang.tongdarailway.railway.planner.StationPlanner;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.*;
import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;

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

    public RailwayMap(RegionPos regionPos) {
        this.regionPos = regionPos;
    }

    // 规划铁路路线方法
    public void startPlanningRoutes(WorldGenRegion level) {
        var builder = RailwayBuilder.getInstance(level.getSeed());
        try {
            // 生成车站位置和连接规划
            RoutePlanner routePlanner = new RoutePlanner();
            StationPlanner stationPlanner = new StationPlanner(regionPos);
            stations.addAll(
                    StationPlanner.generateStation(regionPos, level.getLevel(), level.getSeed())
                            .stream().map(Pair::getFirst).toList()
            );
            var connections = stationPlanner.generateConnections(level.getLevel(), level.getSeed());
            // 生成路线图
            for (StationPlanner.ConnectionGenInfo connection : connections) {
                int[] picStart = connection.connectStart();
                int[] picEnd = connection.connectEnd();
                List<int[]> way = AStarPathfinder.findPath(builder, picStart, Set.of(picEnd), regionPos, 0,
                        (x, y) -> {
                            int scopeLimit = scopeLimit(x, y, picStart, picEnd);
                            int heightLimit = builder.getHeight(x, y) < level.getSeaLevel()+2 ? 100 : 0;
                            return scopeLimit + heightLimit;
                        });
                // 设置出口坐标
                var route = routePlanner.getWay(builder, way, connection, level.getLevel());
                putChunk(route);
            }
        } catch (Exception e) {
            Tongdarailway.LOGGER.error("Err in gen route: ", e);
        }
    }

    /**
     * 添加路径到区块
     * @param route 路径
     */
    private void putChunk(RoutePlanner.ResultWay route) {
        if (route == null)
            return;
        for (CurveRoute.CurveSegment segment : route.way().getSegments()) {
            for (Vec3 p : segment.rasterize(16, 5)) {
                int cx = (int) Math.floor(p.x);
                int cz = (int) Math.floor(p.z);
                if (cx >= regionPos.x()*CHUNK_GROUP_SIZE && cx < (regionPos.x()+1)*CHUNK_GROUP_SIZE && cz >= regionPos.z()*CHUNK_GROUP_SIZE && cz < (regionPos.z()+1)*CHUNK_GROUP_SIZE) {
                    routeMap.computeIfAbsent(new ChunkPos(cx, cz), k -> new HashSet<>())
                            .add(route.way());
                }
            }
        }

        for (TrackPutInfo track : route.trackPutInfos()) {
            var pos = track.pos();
            trackMap.computeIfAbsent(new ChunkPos(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16)), k -> new ArrayList<>()).add(track);
        }
    }

    public static int scopeLimit(int x, int z, int[] picStart, int[] picEnd) {
        // 限制寻路区域
        int maxCost = 10000; // 区域外消耗
        int A = 120;  // 限制区域最大宽度

        double length = new Vec2(picEnd[0]-picStart[0], picEnd[1]-picStart[1]).length();

        Vec3 p = new Vec3(x-picStart[0], 0, z-picStart[1]);

        Vec3 va = new Vec3(picEnd[0]-picStart[0], 0, picEnd[1]-picStart[1]).normalize();
        Vec3 vert = new Vec3(0, 1, 0);
        Vec3 vb = va.cross(vert);

        double a = p.dot(va) / length;
        if (a < 0 || a > 1)
            return maxCost;

        double b = Math.abs(p.dot(vb));
        double py = A * Math.sin(Math.PI * a);

        if (b > py)
            return maxCost;

        return 0;
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