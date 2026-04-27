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
import net.minecraft.server.level.ServerLevel;
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
    public void startPlanningRoutes(ServerLevel level) {
        var builder = RailwayBuilder.getInstance(level.getSeed());
        try {
            // 生成车站位置和连接规划
            RoutePlanner routePlanner = new RoutePlanner();
            StationPlanner stationPlanner = new StationPlanner(regionPos);
            stations.addAll(
                    StationPlanner.generateStation(regionPos, level, level.getSeed())
                            .stream().map(Pair::getFirst).toList()
            );
            var connections = stationPlanner.generateConnections(level, level.getSeed());
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
                var route = routePlanner.getWay(builder, way, connection, level);
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

    public static int scopeLimit(int x, int z, int[] start, int[] end) {
        final float a = 65;
        // 限制寻路区域
        final int maxCost = 10000; // 区域外消耗

        // 计算边长 L 和 AB 方向的单位向量 u
        float ABx = end[0] - start[0];
        float ABz = end[1] - start[1];
        float L = (float) Math.sqrt(ABx * ABx + ABz * ABz);
        if (L == 0) return maxCost; // 退化成点

        float ux = ABx / L;
        float uz = ABz / L;

        // 计算垂直于 AB 方向的单位向量 v（旋转 a 度）
        // 使用 Vec3 的 yRot 方法绕 y 轴旋转
        Vec3 uVec = new Vec3(ux, 0, uz);
        // 绕 y 轴旋转 a 度得到 AD 方向
        Vec3 vVec = uVec.yRot((float) Math.toRadians(a));

        // 四个顶点（按照逆时针顺序）
        float Ax = start[0], Az = start[1];
        float Bx = end[0],   Bz = end[1];
        float Cx = (float) (Bx + vVec.x * L);
        float Cz = (float) (Bz + vVec.z * L);
        float Dx = (float) (Ax + vVec.x * L);
        float Dz = (float) (Az + vVec.z * L);

        // 对四条边做叉积判断（逆时针排列，内部侧叉积 >= 0）
        // 边 AB
        float cross1 = (Bx - Ax) * (z - Az) - (Bz - Az) * (x - Ax);
        if (cross1 < 0) return maxCost;

        // 边 BC
        float cross2 = (Cx - Bx) * (z - Bz) - (Cz - Bz) * (x - Bx);
        if (cross2 < 0) return maxCost;

        // 边 CD
        float cross3 = (Dx - Cx) * (z - Cz) - (Dz - Cz) * (x - Cx);
        if (cross3 < 0) return maxCost;

        // 边 DA
        float cross4 = (Ax - Dx) * (z - Dz) - (Az - Dz) * (x - Dx);
        if (cross4 < 0) return maxCost;

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