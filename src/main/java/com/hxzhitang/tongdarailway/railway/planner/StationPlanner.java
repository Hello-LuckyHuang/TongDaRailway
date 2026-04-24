package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.structure.ModStructureManager;
import com.hxzhitang.tongdarailway.structure.StationTemplate;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

import java.util.*;

import static com.hxzhitang.tongdarailway.Tongdarailway.HEIGHT_MAX_INCREMENT;

// 站点规划 连接规划
public class StationPlanner {
    private final RegionPos regionPos;

    public StationPlanner(RegionPos regionPos) {
        this.regionPos = regionPos;
    }

    // 区域内站点生成
    public static List<Pair<StationGenInfo, List<BlockPos>>> generateStation(RegionPos regionPos, ServerLevel level, long seed) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState cfg = level.getChunkSource().randomState();
        var randomState = RandomState.create(
                ((NoiseBasedChunkGenerator) gen).generatorSettings().value(),
                level.registryAccess().lookupOrThrow(Registries.NOISE),
                level.getSeed()
        );

        long regionSeed = seed + regionPos.hashCode();
        List<Pair<StationGenInfo, List<BlockPos>>> result = new ArrayList<>();

        var nodes = RouteGraph.generate(
                regionPos.getBasePos().x,
                regionPos.getBasePos().x + regionPos.getLength(),
                regionPos.getBasePos().y,
                regionPos.getBasePos().y + regionPos.getLength(),
                6,
                regionSeed,
                70,
                4,
                (x, z) -> {
                    var biome = gen.getBiomeSource().getNoiseBiome((int) x, 65, (int) z, randomState.sampler());
                    return !biome.is(Tags.Biomes.IS_OCEAN);
                }
        );

        for (RouteGraph.NodeData node : nodes) {
            int x = (int) node.point.x;
            int z = (int) node.point.z;
            int y = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE, level, cfg);
            // 使得站点的高度在一定区域内最小
            int miny = 2550;
            int h = miny;
            for (int ix = -2; ix < 3; ix++) {
                for (int iz = -2; iz < 3; iz++) {
                    int ox = ix * 32 + x;
                    int oz = iz * 32 + z;
                    int ty = gen.getBaseHeight(ox, oz, Heightmap.Types.WORLD_SURFACE, level, cfg);
                    miny = Math.min(miny, ty);
                }
            }
            // 确保站点高度在 seaLevel ~ seaLevel + 增量
            h = Math.max(h, level.getSeaLevel());
            h = Math.min(h, level.getSeaLevel() + HEIGHT_MAX_INCREMENT);
            node.setPointY(h);

            // 根据高度决定生成地上还是地下车站
            int exitNum = node.connected.size() >= 4 ? 4 : 2;
            StationTemplate station;
            if (h < y - 10) {
                station = ModStructureManager.getRandomUnderGroundStation(regionSeed, exitNum);
            } else {
                station = ModStructureManager.getRandomNormalStation(regionSeed, exitNum);
            }

            int finalH = h;
            result.add(new Pair<>(
                    new StationGenInfo(station, new BlockPos(x, h, z)),
                    node.connected.stream().map(p -> new BlockPos((int) p.x, finalH, (int) p.z)).toList()
            ));
        }

        return result;
    }

    // 路线连接规则生成
    public List<ConnectionGenInfo> generateConnections(ServerLevel level, long seed) {
        List<ConnectionGenInfo> result = new ArrayList<>();
        Map<Integer, List<StationTemplate.Exit>> connect = new HashMap<>();

        List<Pair<StationGenInfo, List<BlockPos>>> thisStations = generateStation(regionPos, level, seed);

        for (Pair<StationGenInfo, List<BlockPos>> stationGen : thisStations) {
            var station = stationGen.getFirst();
            var connected = stationGen.getSecond();

            var matching = minDistanceMatching(station.getExits(), connected);
            for (Pair<StationTemplate.Exit, BlockPos> exitBlockPosPair : matching) {
                var exit = exitBlockPosPair.getFirst();
                var con = exitBlockPosPair.getSecond();
                int id = station.placePos.getX()+station.placePos.getZ()+con.getX()+con.getZ();
                connect.computeIfAbsent(id, k -> new ArrayList<>()).add(exit);
            }

            var tpos = station.placePos;
            Tongdarailway.LOGGER.info("====> StationPlanner: {} {} {} {}", tpos.getX(), tpos.getY(), tpos.getZ(), regionPos);
        }

        connect.forEach((id, con) -> {
            if (con.size() == 2) {
                var exitA = con.getFirst();
                var exitB = con.getLast();
                result.add(new ConnectionGenInfo(
                        exitA.exitPos().getCenter(),
                        exitA.dir(),
                        exitB.exitPos().getCenter(),
                        exitB.dir(),
                        exitA.pushAway(exitB.exitPos().getCenter()),
                        exitB.pushAway(exitA.exitPos().getCenter())
                ));
            }
        });

        return result;
    }

    public static List<Pair<StationTemplate.Exit, BlockPos>> minDistanceMatching(
            List<StationTemplate.Exit> a,
            List<BlockPos> b
    ) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return new ArrayList<>();
        }

        boolean swapped = false;
        List<StationTemplate.Exit> exits = a;
        List<BlockPos> blocks = b;

        int n = exits.size();
        int m = blocks.size();

        if (n > m) {
            swapped = true;
        }

        int rows = Math.min(n, m);
        int cols = Math.max(n, m);

        double[][] cost = new double[rows][cols];

        if (!swapped) {
            for (int i = 0; i < rows; i++) {
                BlockPos p1 = exits.get(i).exitPos();
                for (int j = 0; j < cols; j++) {
                    cost[i][j] = p1.distSqr(blocks.get(j));
                }
            }
        } else {
            for (int i = 0; i < rows; i++) {
                BlockPos p1 = blocks.get(i);
                for (int j = 0; j < cols; j++) {
                    cost[i][j] = p1.distSqr(exits.get(j).exitPos());
                }
            }
        }

        int[] match = hungarian(cost);

        List<Pair<StationTemplate.Exit, BlockPos>> result = new ArrayList<>();

        if (!swapped) {
            for (int i = 0; i < rows; i++) {
                int j = match[i];
                if (j >= 0) {
                    result.add(new Pair<>(exits.get(i), blocks.get(j)));
                }
            }
        } else {
            for (int i = 0; i < rows; i++) {
                int j = match[i];
                if (j >= 0) {
                    result.add(new Pair<>(exits.get(j), blocks.get(i)));
                }
            }
        }

        return result;
    }

    private static int[] hungarian(double[][] cost) {
        int n = cost.length;
        int m = cost[0].length;

        double[] u = new double[n + 1];
        double[] v = new double[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];

        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;

            double[] minv = new double[m + 1];
            boolean[] used = new boolean[m + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);

            do {
                used[j0] = true;
                int i0 = p[j0];

                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;

                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        double cur = cost[i0 - 1][j - 1] - u[i0] - v[j];

                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }

                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }

                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }

                j0 = j1;
            } while (p[j0] != 0);

            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] match = new int[n];
        Arrays.fill(match, -1);

        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                match[p[j] - 1] = j - 1;
            }
        }

        return match;
    }

    // 站点放置信息(世界坐标系)
    public record StationGenInfo(
            StationTemplate stationTemplate,
            BlockPos placePos
    ) {
        public List<StationTemplate.Exit> getExits() {
            return stationTemplate.getExitsPos(placePos);
        }

        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", stationTemplate.getId());
            tag.putString("type", stationTemplate.getType().name());
            tag.putInt("x", placePos.getX());
            tag.putInt("y", placePos.getY());
            tag.putInt("z", placePos.getZ());
            return tag;
        }

        public static StationGenInfo fromNBT(CompoundTag tag) {
            int id = tag.getInt("id");
            int x = tag.getInt("x");
            int y = tag.getInt("y");
            int z = tag.getInt("z");
            StationTemplate.StationType type = StationTemplate.StationType.valueOf(tag.getString("type"));
            StationTemplate stationTemplate = ModStructureManager.station2.getById(id);
            if (stationTemplate == null)
                stationTemplate = ModStructureManager.station4.getById(id);

            return new StationGenInfo(stationTemplate, new BlockPos(x, y, z));
        }
    }

    /**
     * @param start        起点坐标
     * @param startDir     起点方向
     * @param end          终点坐标
     * @param endDir       终点方向
     * @param connectStart 寻路起点
     * @param connectEnd   寻路终点
     */ // 路线连接信息(世界坐标系)
    public record ConnectionGenInfo(
            Vec3 start,
            Vec3 startDir,
            Vec3 end,
            Vec3 endDir,
            int[] connectStart,
            int[] connectEnd
    ) {

    }
}
