package com.hxzhitang.tongdarailway.worldgen;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.blocks.ITrackPreGenExtension;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RailwayMap;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.railway.planner.StationPlanner;
import com.hxzhitang.tongdarailway.structure.RailwayTemplate;
import com.hxzhitang.tongdarailway.structure.RoadbedManager;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.CurveRoute;
import com.hxzhitang.tongdarailway.util.MyMth;
import com.mojang.serialization.Codec;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.track.*;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
        /*if (Config.generateTrackSpawner) {
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
        }*/

        // 咱就是说，能不能把文件编码改成UTF-8？IDEA一打开就是乱码
        // 放置铁轨
        if (builder.regionRailways.containsKey(regionPos) && railwayMap.trackMap.containsKey(cPos)) {
            List<TrackPutInfo> tracks = railwayMap.trackMap.get(cPos);
            tracks.forEach(track -> {
                if (track.bezier() != null) {
                    if (Math.abs(track.bezier().endOffset().y) > 15){
                        Tongdarailway.LOGGER.warn("Railway track height offset is too large. Generation Failed at" + track.pos().toString());
                        return;
                    }
                    placeCurveTrack(world, track);
                } else {
                    if (!world.getBlockState(track.pos()).is(AllBlocks.TRACK)) {
                        world.setBlock(track.pos(), AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, track.shape()), 3);
                    }
                }
            });
        }

        return true;
    }

    private void placeCurveTrack(WorldGenLevel world, TrackPutInfo track) {
        BlockPos startPos = track.pos();
        world.setBlock(startPos, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, track.shape()).setValue(TrackBlock.HAS_BE,true), 3);

        Vec3 offset = track.bezier().endOffset();
        BlockPos endPos = startPos.offset((int) offset.x, (int) offset.y, (int) offset.z);
        world.setBlock(endPos, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, track.endShape()).setValue(TrackBlock.HAS_BE,true), 3);

        Vec3 start1 = track.bezier().start().add(getStartVec(track.bezier().startAxis()));
        Vec3 start2 = track.bezier().start().add(track.bezier().endOffset()).add(getStartVec(track.bezier().endAxis()));

        Vec3 axis1 = track.bezier().startAxis();  // X轴正方向
        Vec3 axis2 = track.bezier().endAxis();  // Z轴正方向

        Vec3 normal1 = new Vec3(0, 1, 0);
        Vec3 normal2 = new Vec3(0, 1, 0);

        BezierConnection connection = new BezierConnection(
                Couple.create(startPos, endPos),
                Couple.create(start1, start2),
                Couple.create(axis1, axis2),
                Couple.create(normal1, normal2),
                true,  // teToTe
                false, // hasGirder
                TrackMaterial.ANDESITE
        );

        var tbe1 = world.getBlockEntity(startPos);
        var tbe2 = world.getBlockEntity(endPos);

        if(tbe1 != null && tbe2 != null){
            ((ITrackPreGenExtension) tbe1).addConnectionToPreGen(connection);
            ((ITrackPreGenExtension) tbe2).addConnectionToPreGen(connection.secondary());
        }
    }

    private static Vec3 getStartVec(Vec3 dir) {
        double offX;
        if (Math.abs(dir.x) < 1e-6) {
            offX = 0.5;
        } else if (dir.x < 0) {
            offX = 0;
        } else {
            offX = 1;
        }

        double offZ;
        if (Math.abs(dir.z) < 1e-6) {
            offZ = 0.5;
        } else if (dir.z < 0) {
            offZ = 0;
        } else {
            offZ = 1;
        }

        return new Vec3(offX, 0, offZ);
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