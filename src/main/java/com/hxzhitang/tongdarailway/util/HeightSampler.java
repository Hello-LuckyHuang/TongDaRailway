package com.hxzhitang.tongdarailway.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 参考 Railways Untold 的逐点噪声采样方法，根据世界坐标返回整数高度，无需加载区块。
 */
public final class HeightSampler {
    private static final int MARCH_STEP = 8;
    private static final int HINT_WINDOW_BLOCKS = 24;
    private static final int HINT_MAX_DISTANCE_BLOCKS = 64;
    private static final int NO_HEIGHT = Integer.MIN_VALUE;
    private static final double OCEAN_CONTINENTALNESS_THRESHOLD = -0.19;

    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final LevelHeightAccessor heightAccessor;
    private final DensityFunction finalDensity;
    private final int minY;
    private final int maxY;
    private final int seaLevel;
    // 每个采样器的各线程独立保存上一列的坐标和高度，避免不同维度、线程之间相互干扰。
    private final ThreadLocal<int[]> lastColumn = ThreadLocal.withInitial(
            () -> new int[]{0, 0, NO_HEIGHT}
    );

    private HeightSampler(ChunkGenerator generator, RandomState randomState,
                          LevelHeightAccessor heightAccessor, int seaLevel) {
        this.generator = generator;
        this.randomState = randomState;
        this.heightAccessor = heightAccessor;
        this.finalDensity = generator instanceof NoiseBasedChunkGenerator
                ? randomState.router().finalDensity()
                : null;
        this.minY = heightAccessor.getMinBuildHeight();
        this.maxY = heightAccessor.getMaxBuildHeight();
        this.seaLevel = seaLevel;
    }

    /** 使用指定世界的生成器、噪声状态和高度范围创建采样器。 */
    public static HeightSampler forLevel(ServerLevel level) {
        return new HeightSampler(
                level.getChunkSource().getGenerator(),
                level.getChunkSource().randomState(),
                level,
                level.getSeaLevel()
        );
    }

    /**
     * 返回实体地形上方的第一格 Y 坐标；疑似海洋位置的高度最低取海平面。
     * 逐点密度采样是对生成地形的近似计算，不计算地表特征和含水层，支持多线程并发调用。
     *
     * @param x 世界方块 X 坐标
     * @param z 世界方块 Z 坐标
     * @return 采样得到的整数高度
     */
    public int getHeight(int x, int z) {
        int floor;
        if (finalDensity != null) {
            int[] last = lastColumn.get();
            int hint = last[2] != NO_HEIGHT
                    && Math.max(Math.abs((long) x - last[0]), Math.abs((long) z - last[1]))
                    <= HINT_MAX_DISTANCE_BLOCKS ? last[2] : NO_HEIGHT;
            floor = sampleOceanFloor(x, z, hint);
            last[0] = x;
            last[1] = z;
            last[2] = floor;
        } else {
            // 非噪声生成器回退到生成器自身的基础高度查询。
            floor = generator.getBaseHeight(
                    x, z, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState
            );
        }

        if (floor < seaLevel && randomState.router().continents().compute(
                new DensityFunction.SinglePointContext(x, seaLevel, z)
        ) < OCEAN_CONTINENTALNESS_THRESHOLD) {
            return seaLevel;
        }
        return floor;
    }

    /** 优先在相邻列高度附近搜索，未命中时再扫描整个高度范围。 */
    private int sampleOceanFloor(int x, int z, int hint) {
        if (hint != NO_HEIGHT) {
            int top = Math.min(maxY - 1, hint + HINT_WINDOW_BLOCKS);
            if (!isSolid(x, top, z)) {
                int floor = marchDown(x, z, top, Math.max(minY, hint - HINT_WINDOW_BLOCKS));
                if (floor != NO_HEIGHT) {
                    return floor;
                }
            }
        }

        int floor = marchDown(x, z, maxY - 1, minY);
        return floor == NO_HEIGHT ? minY : floor;
    }

    /** 每隔 8 格向下采样，遇到实体后用二分法细化高度；未找到时返回 NO_HEIGHT。 */
    private int marchDown(int x, int z, int top, int bottom) {
        int airAbove = top;
        for (int y = top; y >= bottom; y -= MARCH_STEP) {
            if (isSolid(x, y, z)) {
                return binaryRefine(x, z, airAbove, y) + 1;
            }
            airAbove = y;
        }
        return NO_HEIGHT;
    }

    /** 在上方空气与下方实体之间二分搜索，返回实体边界的 Y 坐标。 */
    private int binaryRefine(int x, int z, int airAbove, int solidBelow) {
        while (airAbove - solidBelow > 1) {
            int mid = (airAbove + solidBelow) >> 1;
            if (isSolid(x, mid, z)) {
                solidBelow = mid;
            } else {
                airAbove = mid;
            }
        }
        return solidBelow;
    }

    /** 最终密度大于 0 时，将该坐标视为实体地形。 */
    private boolean isSolid(int x, int y, int z) {
        return finalDensity.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0;
    }
}
