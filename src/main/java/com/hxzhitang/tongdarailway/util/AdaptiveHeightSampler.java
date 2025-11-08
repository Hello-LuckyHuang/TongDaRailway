package com.hxzhitang.tongdarailway.util;

import java.util.concurrent.*;

public class AdaptiveHeightSampler {
    private final double threshold;          // 极差阈值
    private final int maxLevel;             // 最大层数
    private final int samplesPerNode;       // 每个节点采样点数 (n x n)
    private QuadTree root;            // 四叉树根节点
    private final ExecutorService executor; // 线程池
    private final HeightFunction heightFunction;
    private CountDownLatch latch = null;
    int count = 0;

    // 四叉树节点类
    private class QuadTree {
        double minX, minZ, maxX, maxZ; // 节点边界
        int level;                     // 节点层级
        double minHeight, maxHeight;   // 高度范围
        QuadTree[] children;           // 四个子节点
        boolean isLeaf;                // 是否为叶节点
        double[][] heightSamples;      // 采样点高度值

        QuadTree(double minX, double minZ, double maxX, double maxZ, int level) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.level = level;
            this.children = null;
            this.isLeaf = true;
        }
    }

    // 构造函数
    public AdaptiveHeightSampler(double threshold, int maxLevel, int samplesPerNode, HeightFunction heightFunction) {
        this.threshold = threshold;
        this.maxLevel = maxLevel;
        this.samplesPerNode = samplesPerNode;
        this.heightFunction = heightFunction;
        // 创建固定大小的线程池，根据CPU核心数调整
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.executor = Executors.newFixedThreadPool(coreCount);
    }

    /**
     * 构建自适应四叉树
     * @param regionSize 区域大小 (正方形区域边长)
     */
    public void buildQuadTree(double regionSize) throws InterruptedException {
        latch = new CountDownLatch(1);
        addCount();
        root = new QuadTree(0, 0, regionSize, regionSize, 0);
        buildNode(root);
        latch.await();
    }

    /**
     * 递归构建四叉树节点
     */
    private void buildNode(QuadTree node) {
        // 采样当前节点
        sampleNode(node);

        // 检查是否需要继续分割
        if (shouldSplit(node)) {
            splitNode(node);

            // 使用多线程并行处理四个子节点
            processChildrenInParallel(node);
        }
        subCount();
    }

    /**
     * 使用多线程并行处理四个子节点
     */
    private void processChildrenInParallel(QuadTree parent) {
        // 为每个子节点提交任务到线程池
        for (int i = 0; i < 4; i++) {
            addCount();
            final QuadTree child = parent.children[i];
            executor.submit(() -> buildNode(child));
        }
    }

    /**
     * 对节点进行采样
     */
    private void sampleNode(QuadTree node) {
        int n = samplesPerNode;
        node.heightSamples = new double[n][n];
        node.minHeight = Double.MAX_VALUE;
        node.maxHeight = Double.MIN_VALUE;

        double stepX = (node.maxX - node.minX) / (n - 1);
        double stepZ = (node.maxZ - node.minZ) / (n - 1);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double x = node.minX + i * stepX;
                double z = node.minZ + j * stepZ;
                double height = getHeight(x, z);

                node.heightSamples[i][j] = height;
                node.minHeight = Math.min(node.minHeight, height);
                node.maxHeight = Math.max(node.maxHeight, height);
            }
        }
    }

    /**
     * 判断节点是否需要分割
     */
    private boolean shouldSplit(QuadTree node) {
        // 达到最大层数则不再分割
        if (node.level >= maxLevel) {
            return false;
        }

        // 极差大于阈值则需要分割
        double range = node.maxHeight - node.minHeight;
        return range > threshold;
    }

    /**
     * 分割节点为四个子节点
     */
    private void splitNode(QuadTree node) {
        node.isLeaf = false;
        node.children = new QuadTree[4];

        double midX = (node.minX + node.maxX) / 2.0;
        double midZ = (node.minZ + node.maxZ) / 2.0;
        int nextLevel = node.level + 1;

        // 创建四个子节点
        node.children[0] = new QuadTree(node.minX, node.minZ, midX, midZ, nextLevel); // 左下
        node.children[1] = new QuadTree(midX, node.minZ, node.maxX, midZ, nextLevel); // 右下
        node.children[2] = new QuadTree(node.minX, midZ, midX, node.maxZ, nextLevel); // 左上
        node.children[3] = new QuadTree(midX, midZ, node.maxX, node.maxZ, nextLevel); // 右上
    }

    /**
     * 获取指定位置的高度值（需要用户实现）
     */
    public double getHeight(double x, double z) {
        return heightFunction.getHeight(x, z);
    }

    /**
     * 生成指定大小的插值图像
     * @param width 图像宽度
     * @param height 图像高度
     * @return 高度值数组（已转换为int）
     */
    public int[][] generateImage(int width, int height) {
        int[][] image = new int[width][height];
        double scaleX = root.maxX / width;
        double scaleZ = root.maxZ / height;

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                double worldX = x * scaleX;
                double worldZ = z * scaleZ;
                image[x][z] = (int) getInterpolatedHeight(worldX, worldZ);
            }
        }

        return image;
    }

    /**
     * 获取插值后的高度值
     */
    private double getInterpolatedHeight(double x, double z) {
        return getHeightFromNode(root, x, z);
    }

    /**
     * 从四叉树节点中获取高度值（递归）
     */
    private double getHeightFromNode(QuadTree node, double x, double z) {
        if (node.isLeaf) {
            // 在叶节点中进行双线性插值
            return bilinearInterpolate(node, x, z);
        }

        // 确定点位于哪个子节点
        double midX = (node.minX + node.maxX) / 2.0;
        double midZ = (node.minZ + node.maxZ) / 2.0;

        int childIndex;
        if (x < midX) {
            if (z < midZ) {
                childIndex = 0; // 左下
            } else {
                childIndex = 2; // 左上
            }
        } else {
            if (z < midZ) {
                childIndex = 1; // 右下
            } else {
                childIndex = 3; // 右上
            }
        }

        return getHeightFromNode(node.children[childIndex], x, z);
    }

    /**
     * 双线性插值
     */
    private double bilinearInterpolate(QuadTree node, double x, double z) {
        int n = samplesPerNode;
        double stepX = (node.maxX - node.minX) / (n - 1);
        double stepZ = (node.maxZ - node.minZ) / (n - 1);

        // 计算在采样网格中的位置
        double gridX = (x - node.minX) / stepX;
        double gridZ = (z - node.minZ) / stepZ;

        int x1 = (int) Math.floor(gridX);
        int z1 = (int) Math.floor(gridZ);
        int x2 = Math.min(x1 + 1, n - 1);
        int z2 = Math.min(z1 + 1, n - 1);

        // 边界检查
        x1 = Math.max(0, x1);
        z1 = Math.max(0, z1);

        double dx = gridX - x1;
        double dz = gridZ - z1;

        // 双线性插值
        double h1 = node.heightSamples[x1][z1];
        double h2 = node.heightSamples[x2][z1];
        double h3 = node.heightSamples[x1][z2];
        double h4 = node.heightSamples[x2][z2];

        double interpolated = h1 * (1 - dx) * (1 - dz) +
                h2 * dx * (1 - dz) +
                h3 * (1 - dx) * dz +
                h4 * dx * dz;

        return interpolated;
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 获取四叉树统计信息
     */
    public void printStatistics() {
        Stats stats = new Stats();
        collectStats(root, stats);

        System.out.println("QTree Statistics Info:");
        System.out.println("Node Count: " + stats.totalNodes);
        System.out.println("Leaf Node Count: " + stats.leafNodes);
        System.out.println("Deep: " + stats.maxDepth);
        System.out.println("Sample Point Count: " + stats.totalSamples);
    }

    private void collectStats(QuadTree node, Stats stats) {
        stats.totalNodes++;
        stats.maxDepth = Math.max(stats.maxDepth, node.level);
        stats.totalSamples += samplesPerNode * samplesPerNode;

        if (node.isLeaf) {
            stats.leafNodes++;
        } else {
            for (QuadTree child : node.children) {
                collectStats(child, stats);
            }
        }
    }

    private synchronized void addCount() {
        count++;
    }

    private synchronized void subCount() {
        count--;
        if (count == 0)
            latch.countDown();
    }

    private static class Stats {
        int totalNodes = 0;
        int leafNodes = 0;
        int maxDepth = 0;
        int totalSamples = 0;
    }

    @FunctionalInterface
    public interface HeightFunction {
        double getHeight(double x, double z);
    }

    // 测试示例
    /*
    public static void main(String[] args) {
        // 创建采样器：阈值=5.0，最大层数=6，每个节点4x4采样
        AdaptiveHeightSampler sampler = new AdaptiveHeightSampler(5.0, 6, 4, (x, z) ->{
            // 这里是一个示例函数，实际使用时应该替换为真实的高度函数
            // 例如：return Math.sin(x) * Math.cos(z) * 10;
            return Math.sin(x * 0.1) * Math.cos(z * 0.1) * 20 +
                    Math.sin(x * 0.05) * Math.cos(z * 0.03) * 10;
        });

        try {
            // 构建四叉树，区域大小100x100
            long startTime = System.currentTimeMillis();
            sampler.buildQuadTree(100.0);
            long endTime = System.currentTimeMillis();

            // 打印统计信息
            sampler.printStatistics();
            System.out.println("构建时间: " + (endTime - startTime) + "ms");

            // 生成512x512的图像
            int[][] heightMap = sampler.generateImage(512, 512);

            System.out.println("图像生成完成，尺寸: " + heightMap.length + "x" + heightMap[0].length);
            System.out.println("示例高度值: " + heightMap[256][256]);

        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            // 确保关闭线程池
            sampler.shutdown();
        }
    }*/
}