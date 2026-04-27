package com.hxzhitang.tongdarailway.railway;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.util.AdaptiveHeightSampler;
import com.hxzhitang.tongdarailway.util.ModSaveData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;
import static com.hxzhitang.tongdarailway.railway.RailwayMap.samplingNum;

public class RailwayBuilder {
    private static RailwayBuilder instance;
    private static long seed;

    public final Map<RegionPos, CompletableFuture<RailwayMap>> regionRailways = new ConcurrentHashMap<>();
    public final Map<RegionPos, int[][]> regionHeightMap = new ConcurrentHashMap<>();

    private final ServerLevel level;

    private RailwayBuilder(ServerLevel level) {
        this.level = level;
    }
    public static synchronized RailwayBuilder getInstance(long seed, ServerLevel level) {
        if (instance == null || RailwayBuilder.seed != seed) {
            instance = new RailwayBuilder(level);
            RailwayBuilder.seed = seed;
        }
        return instance;
    }

    public static synchronized RailwayBuilder getInstance(long seed) {
        if (instance == null || RailwayBuilder.seed != seed) {
            return null;
        }
        return instance;
    }

    public CompletableFuture<RailwayMap> prepareIfNeeded(RegionPos regionPos) {
        return regionRailways.computeIfAbsent(regionPos, this::generateRailway);
    }

    public Optional<RailwayMap> getMapIfReady(RegionPos regionPos) {
        CompletableFuture<RailwayMap> future = regionRailways.get(regionPos);
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
            return Optional.empty();
        }

        return Optional.of(future.join());
    }

    // 为区块生成铁路路线。如未生成则阻塞线程开始生成。如已生成直接返回。
    // 这里只生成规划路线，不实际放置路线！
    public CompletableFuture<RailwayMap> generateRailway(RegionPos regionPos) {
        CompletableFuture<RailwayMap> future = CompletableFuture.supplyAsync(() -> {
            // 尝试从本地数据中读取
            ModSaveData data  = ModSaveData.get(Objects.requireNonNull(level.getServer()).getLevel(ServerLevel.OVERWORLD));
            RailwayMap savedData = data.getRailwayMap(regionPos);
            if (savedData != null) {
                Tongdarailway.LOGGER.info("Region {} Done! Read From Local Data", regionPos);
                return savedData;
            }

            // 生成铁路步骤...
            RailwayMap railwayMap = new RailwayMap(regionPos);
            railwayMap.startPlanningRoutes(level);

            //将数据保存到磁盘
            data.putRailwayMap(regionPos, railwayMap);

            return railwayMap;
        });

        future.whenComplete((rules, throwable) -> {
            if (throwable != null) {
                regionRailways.remove(regionPos, future);
                Tongdarailway.LOGGER.error(
                        "Failed to prepare region rules for {}",
                        regionPos,
                        throwable
                );
            }
        });

        return future;
    }

    public static void clearAll() {
        if (instance != null) {
            instance.regionRailways.clear();
            instance.regionHeightMap.clear();
            instance = null;
        }
    }


    /*
     * 缓存图管理
     * 旨在删除主播之前写的错乱的各种坐标系统
     * 统一使用世界坐标
     *
     * */
    /**
     * 获取指定坐标的高度
     * @param wx 世界坐标x
     * @param wz 世界坐标z
     * @return 高度
     */
    public int getHeight(int wx, int wz) {
        RegionPos regionPos = new RegionPos(Math.floorDiv(wx, 16*CHUNK_GROUP_SIZE), Math.floorDiv(wz, 16*CHUNK_GROUP_SIZE));
        int[][] heightMap = regionHeightMap
                .computeIfAbsent(regionPos, k -> getHeightMap(level.getLevel(), regionPos));
        int px = Math.floorDiv(wx - regionPos.x()*CHUNK_GROUP_SIZE*16, 16/samplingNum);
        int pz = Math.floorDiv(wz - regionPos.z()*CHUNK_GROUP_SIZE*16, 16/samplingNum);
        return heightMap[px][pz];
    }

    private int[][] getHeightMap(ServerLevel serverLevel, RegionPos regionPos) {
        // 高度自适应采样地形高度图
        ChunkGenerator gen = serverLevel.getChunkSource().getGenerator();
        RandomState cfg = serverLevel.getChunkSource().randomState();

        // 创建采样器：阈值=10，最大层数=3，每个节点4x4采样
        AdaptiveHeightSampler sampler = new AdaptiveHeightSampler(10, 2, 4, (x, z) -> {
            int wx = (int) (x*(16.0/samplingNum) + regionPos.x()*CHUNK_GROUP_SIZE*16);
            int wz = (int) (z*(16.0/samplingNum) + regionPos.z()*CHUNK_GROUP_SIZE*16);
            return gen.getBaseHeight(wx, wz, Heightmap.Types.WORLD_SURFACE_WG, serverLevel, cfg);
        });

        try {
            long startTime = System.currentTimeMillis();
            // 构建四叉树，区域大小
            sampler.buildQuadTree(CHUNK_GROUP_SIZE*samplingNum);
            long endTime = System.currentTimeMillis();
//            sampler.printStatistics();
            Tongdarailway.LOGGER.info(" Region {} Build HeightMap time: {}ms", regionPos, endTime - startTime);
        } catch (InterruptedException e) {
            Tongdarailway.LOGGER.error("Build HeightMap Err", e);
        } finally {
            sampler.shutdown();
        }

        return sampler.generateImage(CHUNK_GROUP_SIZE*samplingNum, CHUNK_GROUP_SIZE*samplingNum);
    }
}
