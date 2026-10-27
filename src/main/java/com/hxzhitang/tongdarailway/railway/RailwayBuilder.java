package com.hxzhitang.tongdarailway.railway;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.util.AdaptiveHeightSampler;
import com.hxzhitang.tongdarailway.util.ModSaveData;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
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
import java.util.concurrent.atomic.AtomicInteger;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;
import static com.hxzhitang.tongdarailway.railway.RailwayMap.samplingNum;

public class RailwayBuilder {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final AtomicInteger NEXT_POOL_ID = new AtomicInteger();
    private static RailwayBuilder instance;

    private final long seed;
    private final MinecraftServer server;
    private final WorldGenRegion level;
    private final Object taskLock = new Object();
    private volatile boolean closing;
    private volatile boolean cancelled;

    private final Map<RegionPos, Future<?>> regionFutures = new ConcurrentHashMap<>();
    public final Map<RegionPos, RailwayMap> regionRailways = new ConcurrentHashMap<>();
    public final Map<RegionPos, int[][]> regionHeightMap = new ConcurrentHashMap<>();
    private final Map<net.minecraft.resources.ResourceKey<Level>, ConcurrentMap<Long, Integer>> exactHeightCache =
            new ConcurrentHashMap<>();
    private final Map<net.minecraft.resources.ResourceKey<Level>, ConcurrentMap<Long, Boolean>> oceanBiomeCache =
            new ConcurrentHashMap<>();
    private final ThreadPoolExecutor regionRailwayLoadPoolExecutor;

    private RailwayBuilder(long seed, WorldGenRegion level) {
        this.seed = seed;
        this.level = level;
        this.server = Objects.requireNonNull(level.getServer());

        int poolId = NEXT_POOL_ID.incrementAndGet();
        AtomicInteger nextThreadId = new AtomicInteger();
        this.regionRailwayLoadPoolExecutor = new ThreadPoolExecutor(
                64,
                1024,
                1,
                TimeUnit.DAYS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "TongDaRailway-region-planner-" + poolId + "-" + nextThreadId.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    public static RailwayBuilder getInstance(long seed, WorldGenRegion level) {
        RailwayBuilder previous = null;
        RailwayBuilder current;
        synchronized (RailwayBuilder.class) {
            if (instance == null || instance.server != level.getServer() || instance.seed != seed) {
                previous = instance;
                instance = new RailwayBuilder(seed, level);
            }
            current = instance;
        }

        if (previous != null) {
            previous.close();
        }
        return current;
    }

    public static synchronized RailwayBuilder getInstance(long seed) {
        if (instance == null || instance.seed != seed || instance.closing) {
            return null;
        }
        return instance;
    }

    public static void onServerStopped(MinecraftServer server) {
        RailwayBuilder previous;
        synchronized (RailwayBuilder.class) {
            if (instance == null || instance.server != server) {
                return;
            }
            previous = instance;
            instance = null;
        }

        // Workers may need the class lock while finishing, so wait outside it.
        previous.close();
    }

    private void close() {
        synchronized (taskLock) {
            if (closing) {
                return;
            }
            closing = true;
            regionRailwayLoadPoolExecutor.shutdown();
        }

        try {
            if (!regionRailwayLoadPoolExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Tongdarailway.LOGGER.warn(
                        "Cancelling unfinished railway planning tasks after {} seconds",
                        SHUTDOWN_TIMEOUT_SECONDS
                );
                cancelPlanning();
                if (!regionRailwayLoadPoolExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Tongdarailway.LOGGER.warn(
                            "Railway planning pool did not terminate after cancellation; active tasks: {}",
                            regionRailwayLoadPoolExecutor.getActiveCount()
                    );
                }
            }
        } catch (InterruptedException exception) {
            cancelPlanning();
            Thread.currentThread().interrupt();
        } finally {
            if (regionRailwayLoadPoolExecutor.isTerminated()) {
                regionFutures.clear();
                regionRailways.clear();
                regionHeightMap.clear();
                exactHeightCache.clear();
                oceanBiomeCache.clear();
            }
        }
    }

    private void cancelPlanning() {
        synchronized (taskLock) {
            cancelled = true;
            regionFutures.values().forEach(future -> future.cancel(true));
            regionRailwayLoadPoolExecutor.shutdownNow();
        }
    }

    public void checkPlanningCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Railway planning was cancelled");
        }
    }

    public void generateRailway(RegionPos regionPos) {
        // Contain planning failures here so callers can use their existing fallback.
        try {
            generateRailwayInternal(regionPos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Tongdarailway.LOGGER.debug("Interrupted while waiting for railway region {}", regionPos);
        } catch (CancellationException exception) {
            Tongdarailway.LOGGER.debug("Railway planning was cancelled for region {}", regionPos);
        } catch (RejectedExecutionException exception) {
            if (closing || cancelled) {
                Tongdarailway.LOGGER.debug("Railway builder is closing; skipping region {}", regionPos);
            } else {
                Tongdarailway.LOGGER.error("Railway planning task was rejected for region {}", regionPos, exception);
            }
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CancellationException) {
                Tongdarailway.LOGGER.debug("Railway planning was cancelled for region {}", regionPos);
            } else {
                Tongdarailway.LOGGER.error("Failed to plan railway region {}", regionPos, cause);
            }
        } catch (RuntimeException exception) {
            Tongdarailway.LOGGER.error("Unable to prepare railway planning for region {}", regionPos, exception);
        }
    }

    private void generateRailwayInternal(RegionPos regionPos) throws InterruptedException, ExecutionException {
        checkPlanningCancelled();
        Future<?> future;
        synchronized (taskLock) {
            if (closing) {
                throw new CancellationException("Railway builder is closing");
            }
            if (regionRailways.containsKey(regionPos)) {
                return;
            }

            ModSaveData data = ModSaveData.get(server.overworld());
            RailwayMap savedData = data.getRailwayMap(regionPos);
            if (savedData != null) {
                regionRailways.put(regionPos, savedData);
                Tongdarailway.LOGGER.info("Region {} Done! Read From Local Data", regionPos);
                return;
            }

            future = regionFutures.get(regionPos);
            if (future == null) {
                FutureTask<Void> task = new FutureTask<>(() -> {
                    checkPlanningCancelled();
                    RailwayMap railwayMap = new RailwayMap(regionPos);
                    railwayMap.startPlanningRoutes(level, this);

                    // Publish only complete results, atomically with respect to cancellation.
                    synchronized (taskLock) {
                        checkPlanningCancelled();
                        regionRailways.put(regionPos, railwayMap);
                        data.putRailwayMap(regionPos, railwayMap);
                    }
                    return null;
                });
                // The lock keeps submission, registration, and shutdown mutually exclusive.
                regionRailwayLoadPoolExecutor.execute(task);
                regionFutures.put(regionPos, task);
                future = task;
            }
        }

        try {
            future.get();
        } finally {
            synchronized (taskLock) {
                if (future.isDone()) {
                    regionFutures.remove(regionPos, future);
                }
            }
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
        checkPlanningCancelled();
        RegionPos regionPos = new RegionPos(
                Math.floorDiv(wx, 16 * CHUNK_GROUP_SIZE),
                Math.floorDiv(wz, 16 * CHUNK_GROUP_SIZE)
        );
        int[][] heightMap = regionHeightMap.computeIfAbsent(
                regionPos,
                key -> getHeightMap(level.getLevel(), regionPos)
        );
        int px = Math.floorDiv(wx - regionPos.x() * CHUNK_GROUP_SIZE * 16, 16 / samplingNum);
        int pz = Math.floorDiv(wz - regionPos.z() * CHUNK_GROUP_SIZE * 16, 16 / samplingNum);
        return heightMap[px][pz];
    }

    /**
     * Returns an exact world-surface height and caches it for every planning stage.
     * The cache is dimension-aware because planning can query more than one level.
     */
    public int getExactHeight(ServerLevel serverLevel, int wx, int wz) {
        checkPlanningCancelled();
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
        checkPlanningCancelled();
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
            int wx = (int) (x * (16.0 / samplingNum) + regionPos.x() * CHUNK_GROUP_SIZE * 16);
            int wz = (int) (z * (16.0 / samplingNum) + regionPos.z() * CHUNK_GROUP_SIZE * 16);
            return getExactHeight(serverLevel, wx, wz);
        });

        try {
//            long startTime = System.currentTimeMillis();
            // 构建四叉树，区域大小
            sampler.buildQuadTree(CHUNK_GROUP_SIZE * samplingNum);
//            long endTime = System.currentTimeMillis();
//            sampler.printStatistics();
//            Tongdarailway.LOGGER.info(" Region {} Build HeightMap time: {}ms", regionPos, endTime - startTime);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Height sampling was interrupted");
        } finally {
            sampler.shutdown();
        }

        checkPlanningCancelled();
        return sampler.generateImage(CHUNK_GROUP_SIZE * samplingNum, CHUNK_GROUP_SIZE * samplingNum);
    }
}
