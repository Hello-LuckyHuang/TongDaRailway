package com.hxzhitang.tongdarailway.worldgen;

import com.hxzhitang.tongdarailway.Config;
import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import com.hxzhitang.tongdarailway.blocks.TrackSpawnerBlockEntity;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RailwayMap;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.railway.planner.StationPlanner;
import com.hxzhitang.tongdarailway.structure.RailwayTemplate;
import com.hxzhitang.tongdarailway.structure.RoadbedManager;
import com.hxzhitang.tongdarailway.util.CurveRoute;
import com.hxzhitang.tongdarailway.util.MyMth;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.NotNull;

public class RailwayFeature extends Feature<RailwayFeatureConfig> {
    public RailwayFeature(Codec<RailwayFeatureConfig> codec) {
        super(codec);
    }

    @Override
    public boolean place(@NotNull FeaturePlaceContext<RailwayFeatureConfig> ctx) {
        ChunkPos cPos = new ChunkPos(ctx.origin());
        RegionPos regionPos = MyMth.regionPosFromChunkPos(cPos);
        WorldGenLevel world = ctx.level();
        ChunkAccess chunk = world.getChunk(cPos.x, cPos.z);

        RailwayBuilder builder = RailwayBuilder.getInstance(ctx.level().getSeed());
        if (builder == null) return false;

        RailwayMap railwayMap = builder.regionRailways.get(regionPos);
        if (railwayMap == null) return false;

        // 根据路线生成路基
        if (builder.regionRailways.containsKey(regionPos)) {
            if (railwayMap.routeMap.containsKey(cPos)) {
                placeRoadbed(railwayMap, cPos, chunk, world);
            }
        }

        // 放置车站
        for (StationPlanner.StationGenInfo stationPlace : railwayMap.stations) {
            var station = stationPlace.stationStructure();
            if (station == null) continue;
            var pos = stationPlace.placePos();

            station.putSegment(world, cPos, pos);
        }

        // 放置铁轨刷怪笼
        // 也许机械动力铁轨天然厌恶生成时放置，我只能用这种愚蠢的方法了
        if (Config.generateTrackSpawner) {
            if (builder.regionRailways.containsKey(regionPos)) {
                if (railwayMap.trackMap.containsKey(cPos)) {
                    var firstInfo = railwayMap.trackMap.get(cPos).getFirst();
                    BlockPos checkPos = firstInfo.pos().offset(0, -1, 0);
                    if (!world.getBlockState(checkPos).equals(ModBlocks.TRACK_SPAWNER.get().defaultBlockState())) {
                        world.setBlock(checkPos, ModBlocks.TRACK_SPAWNER.get().defaultBlockState(), 3);
                        if (world.getBlockEntity(checkPos) instanceof TrackSpawnerBlockEntity trackSpawner) {
                            trackSpawner.addTrackPutInfo(railwayMap.trackMap.get(cPos));
                        }
                    }
                }
            }
        }

        return true;
    }

    private static void placeRoadbed(RailwayMap railwayMap, ChunkPos cPos, ChunkAccess chunk, WorldGenLevel world) {
        var routes = railwayMap.routeMap.get(cPos);
        for (CurveRoute.CompositeCurve route : routes) {
            int seed = route.getSegments().size();
            RailwayTemplate ground = RoadbedManager.getRandomGround(seed);
            RailwayTemplate bridge = RoadbedManager.getRandomBridge(seed);
            RailwayTemplate tunnel = RoadbedManager.getRandomTunnel(seed);
            // 1.获取一个线上点和一个标架
            var testPoint = new CurveRoute.Point3D(cPos.x*16+8, 80, cPos.z*16+8);
            CurveRoute.NearestPointResult result0 = route.findNearestPoint(testPoint);

            var nearestPoint0 = result0.nearestPoint;
            var frame0 = result0.frame;
            var normal0 = frame0.normal; // 法线

            // 2.扫描平面
            double x0 = nearestPoint0.x, y0 = nearestPoint0.y, z0 = nearestPoint0.z;
            double A = normal0.x, B = normal0.y+1E-5, C = normal0.z;
            double D = -(A*x0 + B*y0 + C*z0);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    // 3.获取标架下坐标
                    // 我们认为路面不会有大幅起伏
                    // 同一个xz只采样一个标架
                    int wx = cPos.x*16 + x;
                    int wz = cPos.z*16 + z;
                    double y = -(A/B)*wx - (C/B)*wz - (D/B);
                    var worldPoint = new CurveRoute.Point3D(wx, y, wz);
                    var result = route.findNearestPoint(worldPoint);
                    var nearest = result.nearestPoint;
                    var frame = CurveRoute.adjustmentFrame(result.frame);

                    double t = route.getGlobalParameter(result.segmentIndex, result.parameter);

                    // 4.根据曲线上高度和实际高度判断应用桥隧
                    BlockPos nearestPos = new BlockPos((int) nearest.x, (int) nearest.y, (int) nearest.z);
                    int h = world.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, nearestPos.getX(), nearestPos.getZ());

                    boolean conditionBridge = nearest.y > h + 10;
                    boolean conditionTunnel = nearest.y < h - 9;

                    // 随机获取一个路基，使用路线段数作为种子来选择
                    RailwayTemplate structureTemplate;
                    if (conditionBridge) {
                        structureTemplate = bridge;
                    } else if (conditionTunnel) {
                        structureTemplate = tunnel;
                    } else {
                        structureTemplate = ground;
                    }

                    for (int oy = structureTemplate.getLowerBound(); oy <= structureTemplate.getUpperBound(); oy++) {
                        double y1 = y + oy;

                        // 计算到曲线点的向量
                        var worldPoint1 = new CurveRoute.Point3D(wx, y1, wz);
                        var vec = worldPoint1.subtract(nearest);

                        if (Math.abs(vec.dot(frame.tangent)) > 3)
                            continue;

                        // 在标架下的坐标
                        double localX = t * route.getTotalLength();    // 沿曲线方向 - 对应原始X
                        double localY = vec.dot(frame.normal);     // 法线方向 - 对应原始Y
                        double localZ = vec.dot(frame.binormal);   // 副法线方向 - 对应原始Z

                        // 5.根据标架下坐标,从模板结构找到对应方块,并且放置
                        BlockState blockState = structureTemplate.getBlockState(localX, localY, localZ);
                        if (blockState != null) {
                            BlockPos blockPos = new BlockPos(x, (int) Math.round(y1), z);
                            chunk.setBlockState(blockPos, blockState, true);
                        }
                    }
                }
            }
        }
    }
}