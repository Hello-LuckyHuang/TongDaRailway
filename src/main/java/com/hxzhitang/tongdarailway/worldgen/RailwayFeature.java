package com.hxzhitang.tongdarailway.worldgen;

import com.hxzhitang.tongdarailway.Config;
import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.blocks.ITrackPreGenExtension;
import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import com.hxzhitang.tongdarailway.blocks.TrackSpawnerBlockEntity;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RailwayMap;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.railway.planner.StationPlanner;
import com.hxzhitang.tongdarailway.structure.ModStructureManager;
import com.hxzhitang.tongdarailway.structure.RailwayTemplate;
import com.hxzhitang.tongdarailway.structure.StationTemplate;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.CurveRoute;
import com.hxzhitang.tongdarailway.util.MyMth;
import com.mojang.serialization.Codec;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.track.*;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
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
            var station = stationPlace.stationTemplate();
            if (station == null) continue;
            var pos = stationPlace.placePos();
            var center = pos.getCenter();

            if (station.getBoundChunks(center).contains(cPos)) {
                placeStation(cPos, center, station, chunk);
            }
        }

        // 放置铁轨刷怪笼
        // 也许机械动力铁轨天然厌恶生成时放置，我只能用这种愚蠢的方法了
        if (Config.useTrackSpawnerPlaceTrack && Config.generateTrackSpawner) {
                if (builder.regionRailways.containsKey(regionPos)) {
                    if (railwayMap.trackMap.containsKey(cPos)) {
                        var firstInfo = railwayMap.trackMap.get(cPos).getFirst();
                        BlockPos checkPos = firstInfo.pos().offset(0, -1, 0);
                        if (!world.getBlockState(checkPos).is(ModBlocks.TRACK_SPAWNER.get())) {
                            world.setBlock(checkPos, ModBlocks.TRACK_SPAWNER.get().defaultBlockState(), 3);
                        }
                        if (world.getBlockEntity(checkPos) instanceof TrackSpawnerBlockEntity trackSpawner) {
                            trackSpawner.addTrackPutInfo(railwayMap.trackMap.get(cPos));
                        }
                    }
                }
        }
        if (!Config.useTrackSpawnerPlaceTrack) {
            // 咱就是说，能不能把文件编码改成UTF-8？IDEA一打开就是乱码
            // 抱歉，似乎我的IDEA一开始就是GBK编码 :-( 确实不想再改了

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

    private static void placeStation(ChunkPos cPos, Vec3 center, StationTemplate station, ChunkAccess chunk) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                var test = new Vec3(cPos.x*16+x, center.y + 1, cPos.z*16+z);
                if (!station.isInVoxel(test.subtract(center)))
                    continue;
                for (int oy = station.getLowerBound(); oy < station.getUpperBound(); oy++) {
                    int y = oy + (int) center.y;
                    var p = new Vec3(cPos.x*16+x, y, cPos.z*16+z);
                    var blockState = station.getBlockState(p.subtract(center));
                    if (blockState == null) {
                        // 应对机械动力蓝图保存的nbt文件不包含空气
                        if (station.isInVoxel(p.subtract(center))) {
                            blockState = Blocks.AIR.defaultBlockState();
                        } else {
                            continue;
                        }
                    }
                    chunk.setBlockState(new BlockPos(x, y, z), blockState, true);
                }
            }
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
        for (CurveRoute route : routes) {
            int seed = route.getSegments().size();
            RailwayTemplate ground = ModStructureManager.getRandomGround(seed);
            RailwayTemplate bridge = ModStructureManager.getRandomBridge(seed);
            RailwayTemplate tunnel = ModStructureManager.getRandomTunnel(seed);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    // 获取一个线上点
                    var testPoint0 = new Vec3(cPos.x*16+x, 80, cPos.z*16+z);
                    CurveRoute.Frame frame = route.getFrame(testPoint0);

                    var nearest0 = frame.nearestPoint;

                    double t = frame.globalT;
                    var normal0 = frame.normal0;
                    var binormal0 = frame.binormal0;

                    Vec3 vec0 = testPoint0.subtract(nearest0);

                    // 根据曲线上高度和实际高度判断应用桥隧
                    BlockPos nearestPos = new BlockPos((int) nearest0.x, (int) nearest0.y, (int) nearest0.z);
                    int h = world.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, nearestPos.getX(), nearestPos.getZ());

                    boolean conditionBridge = nearest0.y > h + 10;
                    boolean conditionTunnel = nearest0.y < h - 9;

                    // 随机获取一个路基，使用路线段数作为种子来选择
                    RailwayTemplate structureTemplate;
                    if (conditionBridge) {
                        structureTemplate = bridge;
                    } else if (conditionTunnel) {
                        structureTemplate = tunnel;
                    } else {
                        structureTemplate = ground;
                    }

                    double localX = 0;
                    var seg = route.getSegment(frame.segmentIndex);
                    if (seg instanceof CurveRoute.BezierSegment curve) {
                        double length = curve.getLength();
                        localX = frame.localU * length;
                        int n = (int) (length / structureTemplate.getWidth());
                        int l = n * structureTemplate.getWidth();
                        double s = (length - l) / 2;
                        if (localX < s)
                            localX = 0;
                        else if (localX >= s && localX < l+s)
                            localX = localX - s;
                        else
                            localX = structureTemplate.getWidth();
                    } else if (seg instanceof CurveRoute.LineSegment line) {
                        Vec3 dir = line.end.subtract(line.start);
                        int a = Mth.equal(dir.x, 0) ? 0 : dir.x > 0 ? 1 : -1;
                        int b = Mth.equal(dir.z, 0) ? 0 : dir.z > 0 ? 1 : -1;
                        localX = Math.abs(a*nearest0.x+b*nearest0.z)/Math.sqrt(a*a+b*b);
                        if (frame.localU > 0.95)
                            localX = structureTemplate.getWidth();
                        else if (frame.localU < 0.05)
                            localX = 0;
                    }


                    double z0 = vec0.dot(binormal0);

                    if (structureTemplate == null || !structureTemplate.isInVoxel(1, 1, z0))
                        continue;

                    for (int oy = structureTemplate.getLowerBound(); oy <= structureTemplate.getUpperBound(); oy++) {
                        int y = oy + (int) nearest0.y;
                        var testPoint = new Vec3(cPos.x*16+x, y, cPos.z*16+z);
                        var vec = testPoint.subtract(nearest0);

                        double localY = vec.dot(normal0) - 0.05;
                        double localZ = vec.dot(binormal0);

                        // 根据标架下坐标,从模板结构找到对应方块,并且放置
                        BlockState blockState = structureTemplate.getBlockState(localX, localY, localZ);
                        if (blockState != null) {
                            BlockPos blockPos = new BlockPos(x, y, z);
                            chunk.setBlockState(blockPos, blockState, true);
                        }
                    }
                    // 向下填充地基直到遇到支撑方块(隧道不考虑向下填充地基)
                    if (conditionTunnel)
                        continue;

                    for (int oy = structureTemplate.getLowerBound() - 1; oy > structureTemplate.getLowerBound() - 100; oy--) {
                        int y = oy + (int) nearest0.y;

                        BlockPos blockPos = new BlockPos(x, y, z);

                        if (chunk.getBlockState(blockPos).isFaceSturdy(world, blockPos, Direction.UP)) {
                            break;
                        }

                        var testPoint = new Vec3(cPos.x*16+x, y, cPos.z*16+z);
                        var vec = testPoint.subtract(nearest0);

                        double localY = vec.dot(normal0) - 0.05;
                        double localZ = vec.dot(binormal0);

                        BlockState blockState = structureTemplate.getBlockState(localX, localY, localZ);
                        if (blockState != null) {
                            chunk.setBlockState(blockPos, blockState, true);
                        }
                    }
                }
            }

        }
    }
}