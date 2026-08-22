package com.hxzhitang.tongdarailway.railway;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.util.AdaptiveHeightSampler;
import com.hxzhitang.tongdarailway.util.ModSaveData;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.common.Tags;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;
import static com.hxzhitang.tongdarailway.railway.RailwayMap.samplingNum;

public class RailwayBuilder {
    private static RailwayBuilder instance;
    private static long seed;

    private final Map<RegionPos, Future<?>> regionFutures = new ConcurrentHashMap<>();
    public final Map<RegionPos, RailwayMap> regionRailways = new ConcurrentHashMap<>();
    public final Map<RegionPos, int[][]> regionHeightMap = new ConcurrentHashMap<>();
    private final Map<net.minecraft.resources.ResourceKey<Level>, ConcurrentMap<Long, Integer>> exactHeightCache = new ConcurrentHashMap<>();
    private final Map<net.minecraft.resources.ResourceKey<Level>, ConcurrentMap<Long, Boolean>> oceanBiomeCache = new ConcurrentHashMap<>();

    private final LinkedBlockingQueue<Runnable> regionRailwayLoadQueue = new LinkedBlockingQueue<Runnable>(); //线程池
    private final ThreadPoolExecutor regionRailwayLoadPoolExecutor = new ThreadPoolExecutor(64, 1024, 1, TimeUnit.DAYS, regionRailwayLoadQueue);
    private final WorldGenRegion level;

    private RailwayBuilder(WorldGenRegion level) {
        this.level = level;
    }
    public static synchronized RailwayBuilder getInstance(long seed, WorldGenRegion level) {
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

    // 为区块生成铁路路线。如未生成则阻塞线程开始生成。如已生成直接返回。
    // 这里只生成规划路线，不实际放置路线！
    public void generateRailway(RegionPos regionPos) {
        // 如果路线已经生成，直接返回
        if (regionRailways.containsKey(regionPos)) {
            return;
        }

        // 尝试从本地数据中读取
        ModSaveData data  = ModSaveData.get(Objects.requireNonNull(level.getServer()).getLevel(ServerLevel.OVERWORLD));
        RailwayMap savedData = data.getRailwayMap(regionPos);
        if (savedData != null) {
            regionRailways.put(regionPos, savedData);
            Tongdarailway.LOGGER.info("Region {} Done! Read From Local Data", regionPos);
            return;
        }

        // 如果路线还未生成...
        try {
            // 如果没有线程在生成路线，添加线程开始生成
            if (!regionFutures.containsKey(regionPos)) {
                var f = regionRailwayLoadPoolExecutor.submit(() -> {
                    // 生成铁路步骤...
                    RailwayMap railwayMap = new RailwayMap(regionPos);

                    railwayMap.startPlanningRoutes(level);

                    // 放置路线规划结果
                    regionRailways.put(regionPos, railwayMap);

                    //将数据保存到磁盘
                    data.putRailwayMap(regionPos, railwayMap);
                });
                regionFutures.put(regionPos, f);
            }
            // 等待线程生成
            regionFutures.get(regionPos).get();
        } catch (InterruptedException | ExecutionException e) {
            Tongdarailway.LOGGER.error(e.getMessage());
        } finally {
            regionFutures.remove(regionPos);
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

    /**
     * Returns an exact world-surface height and caches it for every planning stage.
     * The cache is dimension-aware because planning can query more than one level.
     */
    public int getExactHeight(ServerLevel serverLevel, int wx, int wz) {
        ConcurrentMap<Long, Integer> dimensionCache = exactHeightCache.computeIfAbsent(
                serverLevel.dimension(),
                ignored -> new ConcurrentHashMap<>()
        );
        long key = ((long) wx << 32) ^ (wz & 0xffffffffL);
        return dimensionCache.computeIfAbsent(key, ignored -> {
            ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
            RandomState randomState = serverLevel.getChunkSource().randomState();
            return generator.getBaseHeight(
                    wx,
                    wz,
                    Heightmap.Types.WORLD_SURFACE,
                    serverLevel,
                    randomState
            );
        });
    }

    /** Returns whether the quart containing the position has an ocean biome. */
    public boolean isOceanBiome(ServerLevel serverLevel, int wx, int wz) {
        ConcurrentMap<Long, Boolean> dimensionCache = oceanBiomeCache.computeIfAbsent(
                serverLevel.dimension(),
                ignored -> new ConcurrentHashMap<>()
        );
        int quartX = QuartPos.fromBlock(wx);
        int quartZ = QuartPos.fromBlock(wz);
        long key = ((long) quartX << 32) ^ (quartZ & 0xffffffffL);
        return dimensionCache.computeIfAbsent(key, ignored -> {
            ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
            RandomState randomState = serverLevel.getChunkSource().randomState();
            try {
                return generator.getBiomeSource().getNoiseBiome(
                        quartX,
                        QuartPos.fromBlock(serverLevel.getSeaLevel()),
                        quartZ,
                        randomState.sampler()
                ).is(Tags.Biomes.IS_OCEAN);
            } catch (NullPointerException exception) {
                return false;
            }
        });
    }

    private int[][] getHeightMap(ServerLevel serverLevel, RegionPos regionPos) {
        // 高度自适应采样地形高度图
        // 创建采样器：阈值=10，最大层数=3，每个节点4x4采样
        AdaptiveHeightSampler sampler = new AdaptiveHeightSampler(10, 2, 4, (x, z) -> {
            int wx = (int) (x*(16.0/samplingNum) + regionPos.x()*CHUNK_GROUP_SIZE*16);
            int wz = (int) (z*(16.0/samplingNum) + regionPos.z()*CHUNK_GROUP_SIZE*16);
            return getExactHeight(serverLevel, wx, wz);
        });

        try {
//            long startTime = System.currentTimeMillis();
            // 构建四叉树，区域大小
            sampler.buildQuadTree(CHUNK_GROUP_SIZE*samplingNum);
//            long endTime = System.currentTimeMillis();
//            sampler.printStatistics();
//            Tongdarailway.LOGGER.info(" Region {} Build HeightMap time: {}ms", regionPos, endTime - startTime);
        } catch (InterruptedException e) {
            Tongdarailway.LOGGER.error("Build HeightMap Err", e);
        } finally {
            sampler.shutdown();
        }

        return sampler.generateImage(CHUNK_GROUP_SIZE*samplingNum, CHUNK_GROUP_SIZE*samplingNum);
    }
}
