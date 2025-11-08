package com.hxzhitang.tongdarailway.util;

import com.hxzhitang.tongdarailway.railway.RegionPos;

import java.util.*;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;
import static com.hxzhitang.tongdarailway.railway.RailwayMap.samplingNum;

// 参考：URL_ADDRESS// 参考：https://www.redblobgames.com/pathfinding/a-star/introduction.html
public class AStarPathfinder {

    // 八向移动的方向：上、下、左、右、左上、右上、左下、右下
    private static final int[][] DIRECTIONS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},    // 上下左右
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}    // 斜向
    };

    // 移动损耗：上下左右为1，斜向为√2≈1.414
    private static final double[] MOVEMENT_COST = {
            1.0, 1.0, 1.0, 1.0,                  // 上下左右
            1.414, 1.414, 1.414, 1.414           // 斜向
    };

    public static List<int[]> findPath(int[][] image, int[] start, int[] end, AdditionalCostFunction additionalCostFunction) {
        if (image == null || image.length == 0 || image[0].length == 0) {
            return new ArrayList<>();
        }

        int rows = image.length;
        int cols = image[0].length;

        // 验证起点和终点是否在图像范围内
        if (!isValidCoordinate(start[0], start[1], rows, cols) ||
                !isValidCoordinate(end[0], end[1], rows, cols)) {
            return new ArrayList<>();
        }

        // 优先队列，按f值排序
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));

        // 记录每个节点的g值（从起点到该点的实际代价）
        double[][] gScore = new double[rows][cols];
        for (double[] row : gScore) {
            Arrays.fill(row, Double.MAX_VALUE);
        }

        // 记录每个节点的父节点，用于重建路径
        Node[][] cameFrom = new Node[rows][cols];

        // 初始化起点
        Node startNode = new Node(start[0], start[1]);
        startNode.g = 0;
        startNode.h = heuristic(start, end);
        startNode.f = startNode.g + startNode.h;

        gScore[start[0]][start[1]] = 0;
        openSet.offer(startNode);

        // 记录节点是否在开放集合中
        boolean[][] inOpenSet = new boolean[rows][cols];
        inOpenSet[start[0]][start[1]] = true;

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            int currentX = current.x;
            int currentY = current.y;

            // 如果到达终点，重建路径
            if (currentX == end[0] && currentY == end[1]) {
                return reconstructPath(cameFrom, current);
            }

            inOpenSet[currentX][currentY] = false;

            // 检查所有可能的移动方向
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] direction = DIRECTIONS[i];
                int newX = currentX + direction[0];
                int newY = currentY + direction[1];

                // 检查新坐标是否有效
                if (!isValidCoordinate(newX, newY, rows, cols)) {
                    continue;
                }

                // 计算移动代价
                double movementCost = MOVEMENT_COST[i];
                double pixelCost = Math.abs(image[currentX][currentY] - image[newX][newY]);
                double tentativeG = current.g + movementCost + pixelCost + additionalCostFunction.cost(currentX, currentY);

                // 如果找到更优路径
                if (tentativeG < gScore[newX][newY]) {
                    Node neighbor = new Node(newX, newY);
                    neighbor.g = tentativeG;
                    neighbor.h = heuristic(new int[]{newX, newY}, end);
                    neighbor.f = neighbor.g + neighbor.h;

                    cameFrom[newX][newY] = current;
                    gScore[newX][newY] = tentativeG;

                    if (!inOpenSet[newX][newY]) {
                        openSet.offer(neighbor);
                        inOpenSet[newX][newY] = true;
                    }
                }
            }
        }

        // 如果开放集合为空且未到达终点，说明无路径
        return new ArrayList<>();
    }

    // 检查坐标是否有效
    private static boolean isValidCoordinate(int x, int y, int rows, int cols) {
        return x >= 0 && x < rows && y >= 0 && y < cols;
    }

    // 启发式函数：使用欧几里得距离
    private static double heuristic(int[] a, int[] b) {
        int dx = Math.abs(a[0] - b[0]);
        int dy = Math.abs(a[1] - b[1]);
        return Math.sqrt(dx * dx + dy * dy);
    }

    // 重建路径
    private static List<int[]> reconstructPath(Node[][] cameFrom, Node current) {
        List<int[]> path = new ArrayList<>();

        // 从终点反向追踪到起点
        while (current != null) {
            path.add(0, new int[]{current.x, current.y});
            current = cameFrom[current.x][current.y];
        }

        return path;
    }

    // 节点类
    static class Node {
        int x, y;
        double g; // 从起点到该节点的实际代价
        double h; // 启发式估计代价
        double f; // 总代价 f = g + h

        Node(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    //附加损耗
    @FunctionalInterface
    public interface AdditionalCostFunction {
        double cost(int x, int y);
    }

    public static int[] world2PicPos(int[] worldPos, RegionPos centerRegionPos) {
        int wx = worldPos[0];
        int wz = worldPos[1];
        return new int[] {
                (wx - (centerRegionPos.x()-1)*CHUNK_GROUP_SIZE*16)*samplingNum/16,
                (wz - (centerRegionPos.z()-1)*CHUNK_GROUP_SIZE*16)*samplingNum/16
        };
    }

    public static int[] pic2RegionPos(int[] picPos) {
        int px = picPos[0];
        int pz = picPos[1];
        return new int[] {
                px - CHUNK_GROUP_SIZE*samplingNum,
                pz - CHUNK_GROUP_SIZE*samplingNum,
                picPos[2]
        };
    }
}