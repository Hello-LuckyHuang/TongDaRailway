package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Collectors;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;
import static com.hxzhitang.tongdarailway.Tongdarailway.HEIGHT_MAX_INCREMENT;
import static com.hxzhitang.tongdarailway.railway.RailwayMap.samplingNum;

// 寻路 生成路径曲线
public class RoutePlanner {
    private final RegionPos regionPos;

    public RoutePlanner(RegionPos regionPos) {
        this.regionPos = regionPos;
    }

    // 获得十字形四向区域的损耗图
    public int[][] getCostMap(WorldGenRegion level) {
        int[][] heightMap = new int[CHUNK_GROUP_SIZE*samplingNum*3][CHUNK_GROUP_SIZE*samplingNum*3];
        for (int[] ints : heightMap) {
            Arrays.fill(ints, Integer.MAX_VALUE);
        }
        for (int i = -1; i < 2; i++) {
            for (int j = -1; j < 2; j++) {
                if (Math.abs(i) == 1 && Math.abs(j) == 1)
                    continue;
                RegionPos rPos = new RegionPos(regionPos.x() + i, regionPos.z() + j);
                RailwayBuilder builder = RailwayBuilder.getInstance(level.getSeed());
                int[][] map;
                if (builder != null) {
                    map = builder.regionHeightMap
                            .computeIfAbsent(rPos, k -> getHeightMap(level.getLevel(), rPos));
                } else {
                    map = getHeightMap(level.getLevel(), rPos);
                }
                for (int x = 0; x < map.length; x++) {
                    for (int z = 0; z < map[0].length; z++) {
                        int picX = (i+1)*CHUNK_GROUP_SIZE*samplingNum+x;
                        int picZ = (j+1)*CHUNK_GROUP_SIZE*samplingNum+z;
                        heightMap[picX][picZ] = map[x][z];
                    }
                }
            }
        }

        return heightMap;
    }

    private int[][] getHeightMap(ServerLevel serverLevel, RegionPos regionPos) {
        // 高度自适应采样地形高度图
        ChunkGenerator gen = serverLevel.getChunkSource().getGenerator();
        RandomState cfg = serverLevel.getChunkSource().randomState();

        // 创建采样器：阈值=10，最大层数=3，每个节点4x4采样
        AdaptiveHeightSampler sampler = new AdaptiveHeightSampler(10, 3, 4, (x, z) -> {
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
            Tongdarailway.LOGGER.info(" Build HeightMap time: {}ms", endTime - startTime);
        } catch (InterruptedException e) {
            Tongdarailway.LOGGER.error(e.getMessage());
        } finally {
            sampler.shutdown();
        }

        int[][] heightMap = sampler.generateImage(CHUNK_GROUP_SIZE*samplingNum, CHUNK_GROUP_SIZE*samplingNum);

        return heightMap;
    }

    /**
     * 规划路径
     * @param way 路线图
     */
    public ResultWay getWay(List<int[]> way, int[][] costMap, StationPlanner.ConnectionGenInfo connectionGenInfo, ServerLevel level) {
        List<int[]> handledHeightWay = handleHeight(way, level, costMap, connectionGenInfo);
        // 结果转为中心图坐标系
        handledHeightWay = handledHeightWay.stream().map(AStarPathfinder::pic2RegionPos).toList();
        List<List<int[]>> straightPaths = StraightPathFinder.findStraightPaths(handledHeightWay);
        straightPaths.removeIf(list -> list.size() <= 2);
        var poi = handlePath(straightPaths, 2); // 处理路径
        var route = connectPaths(connectionGenInfo, poi);
        var track = connectTrack(connectionGenInfo, poi);

        return new ResultWay(route, track);
    }

    /**
     * 测高 处理高度
     * @param path 直行路径(区域内坐标)
     * @param level 服务器世界
     */
    public List<int[]> handleHeight(List<int[]> path, ServerLevel level, int[][] heightMap, StationPlanner.ConnectionGenInfo con) {
        List<double[]> adPath = new LinkedList<>();
        int seaLevel = level.getSeaLevel();

        // 测高
        for (int[] p : path) {
            int h = heightMap[p[0]][p[1]];
            // 限制高度范围
            h = Math.max(h, seaLevel + 5);
            h = Math.min(h, seaLevel + HEIGHT_MAX_INCREMENT);
            adPath.add(new double[]{p[0], p[1], h});
        }

        adPath.getFirst()[2] = con.connectStart()[2];
        adPath.getLast()[2] = con.connectEnd()[2];

        // 高度调整
        adPath = adjustmentHeight(adPath);

        //卷积平滑 保持首末点不变
        int max = adPath.stream().mapToInt(p -> (int) p[2]).max().orElse(0);
        int min = adPath.stream().mapToInt(p -> (int) p[2]).min().orElse(0);
        int framed2 = ((max - min) / 2) + 1;

        // -6630103939123469904
        // -560 71 3184
        // 1443514625631274284
        // -1296 116 1262
        if (adPath.size() > framed2*2 && framed2*2 >= 3) {
            // 平滑中间
            List<double[]> adPath1 = new ArrayList<>();
            adPath1.add(adPath.getFirst());
            for (int i = 1; i < adPath.size()-1; i++) {
                double mean = 0;
                int sum = 0;
                for (int j = i-framed2; j <= i+framed2; j++) {
                    if (j >= 0 && j < adPath.size()) {
                        mean += adPath.get(j)[2];
                        sum++;
                    } else if (j < 0) {
                        mean += adPath.getFirst()[2];
                        sum++;
                    } else {
                        mean += adPath.getLast()[2];
                        sum++;
                    }
                }
                mean /= sum;
                adPath1.add(new double[] {adPath.get(i)[0], adPath.get(i)[1], mean});
            }
            adPath1.add(adPath.getLast());
            adPath = adPath1;

            // 平滑起末
            double fh = con.connectStart()[2];
            double lh = con.connectEnd()[2];
            if (adPath.size() > framed2*2+20) {
                for (int i = 1; i < framed2+10; i++) {
                    double t = (double) i / (framed2+10);
                    double sh = adPath.get(i)[2];
                    double eh = adPath.get(adPath.size() - 1 - i)[2];

                    adPath.get(i)[2] = fh * (1 - t) + sh * t;
                    adPath.get(adPath.size() - 1 - i)[2] = lh * (1 - t) + eh * t;
                }
            }
        }

        return adPath.stream()
                .map(arr -> Arrays.stream(arr)
                        .mapToInt(d -> (int) Math.round(d))  // 四舍五入
                        .toArray()
                )
                .collect(Collectors.toList());
    }

    /**
     * 处理路径，裁剪路径留转弯空间并转换为世界坐标系
     * @param straightPaths 带高路径(区域内坐标)
     * @param cutDistance 开始剪裁路径的距离
     * @return 所有直线路径(世界坐标)
     */
    public List<List<Vec3>> handlePath(List<List<int[]>> straightPaths, double cutDistance) {
        // 创建一个副本用于处理，避免修改原始数据
        List<List<int[]>> processedPaths = new ArrayList<>();
        for (List<int[]> path : straightPaths) {
            List<int[]> copy = new ArrayList<>();
            for (int[] point : path) {
                copy.add(new int[]{point[0], point[1], point[2]});
            }
            processedPaths.add(copy);
        }

        // 处理相邻路径段之间的连接和裁剪
        for (int i = 0; i < processedPaths.size() - 1; i++) {
            List<int[]> currentPath = processedPaths.get(i);
            List<int[]> nextPath = processedPaths.get(i + 1);

            if (currentPath.size() < 2 || nextPath.size() < 2) {
                continue;
            }

            // 获取当前路径的终点
            int[] currentEndPoint = currentPath.getLast();
            // 获取下一个路径的起点
            int[] nextStartPoint = nextPath.getFirst();

            // 计算两点之间的距离
            double distance = Math.sqrt(
                    Math.pow(nextStartPoint[0] - currentEndPoint[0], 2) +
                            Math.pow(nextStartPoint[1] - currentEndPoint[1], 2)
            );

            // 如果距离过近，需要裁剪路径为贝塞尔曲线提供空间
            if (distance < cutDistance) { // 阈值可根据需要调整
                // 裁剪当前路径的末尾
                trimPathEnd(currentPath, (int) (cutDistance/2));
                // 裁剪下一个路径的开始
                trimPathStart(nextPath, (int) (cutDistance/2));
            }
        }

        List<List<Vec3>> result = new ArrayList<>();
        for (List<int[]> segment : processedPaths) {
            List<Vec3> path = new ArrayList<>();
            for (int[] point : segment) {
                path.add(MyMth.inRegionPos2WorldPos(
                        regionPos,
                        new Vec3(point[0], point[2], point[1])
                                .multiply(16.0/samplingNum, 1, 16.0/samplingNum)
                ));
            }
            result.add(path);
        }

        return result;
    }

    /**
     * 将直线路径段通过三阶贝塞尔曲线平滑连接
     * @param pathPoi 所有直线路径段的端点
     * @return 连接后的复合曲线
     */
    private CurveRoute.CompositeCurve connectPaths(StationPlanner.ConnectionGenInfo con, List<List<Vec3>> pathPoi) {
        CurveRoute.CompositeCurve compositeCurve = new CurveRoute.CompositeCurve();

        if (pathPoi == null || pathPoi.isEmpty()) {
            return compositeCurve;
        }

        // 起点数据
        List<Vec3> startSegment = pathPoi.getFirst();
        Vec3 firstPos = startSegment.getFirst();
        Vec3 firstDir = firstPos.subtract(startSegment.get(1)).multiply(1,0,1).normalize();
        // 终点数据
        List<Vec3> lastSegment = pathPoi.getLast();
        Vec3 lastPos = lastSegment.getLast();
        Vec3 lastDir = lastPos.subtract(lastSegment.get(lastSegment.size()-2)).multiply(1,0,1).normalize();

        // 车站路线起点连接
        Vec3 startConnectPos;
        Vec3 startConnectDir;
        if (firstPos.distanceTo(con.start()) > lastPos.distanceTo(con.start())) {
            startConnectPos = lastPos;
            startConnectDir = lastDir;
        } else {
            startConnectPos = firstPos;
            startConnectDir = firstDir;
        }
        CurveRoute.CubicBezier startConnect = CurveRoute.CubicBezier.getCubicBezier(
                con.start(),
                con.startDir(),
                startConnectPos.subtract(con.start()),
                startConnectDir
        );
        compositeCurve.addSegment(startConnect);

        // 处理路径
        for (int i = 0; i < pathPoi.size(); i++) {
            // 直线片段
            List<Vec3> segment = pathPoi.get(i);
            for (int j = 0; j < segment.size() - 1; j++) {
                Vec3 pA = segment.get(j);
                Vec3 pB = segment.get(j+1);
                compositeCurve.addSegment(new CurveRoute.LineSegment(pA, pB));
            }
            // 转弯贝塞尔曲线段
            if (i < pathPoi.size() - 1) {
                List<Vec3> nextSegment = pathPoi.get(i+1);
                // 使用贝塞尔曲线连接两段路径
                var vecA = new Vec3(segment.getLast().x(), 0, segment.getLast().z()).subtract(
                        new Vec3(segment.getFirst().x(), 0, segment.getFirst().z()));
                var vecB = new Vec3(nextSegment.getFirst().x(), 0, nextSegment.getFirst().z()).subtract(
                        new Vec3(nextSegment.getLast().x(), 0, nextSegment.getLast().z()));
                var prevDirection = vecA.normalize();
                var currentDirection = vecB.normalize();
                CurveRoute.CubicBezier bezierSegment = CurveRoute.CubicBezier.getCubicBezier(
                        segment.getLast(),
                        prevDirection,                                   // 起点切线方向
                        nextSegment.getFirst().subtract(segment.getLast()),
                        currentDirection                                 // 终点切线方向
                );
                compositeCurve.addSegment(bezierSegment);
            }
        }

        // 车站路线终点连接
        Vec3 endConnectPos;
        Vec3 endConnectDir;
        if (firstPos.distanceTo(con.end()) > lastPos.distanceTo(con.end())) {
            endConnectPos = lastPos;
            endConnectDir = lastDir;
        } else {
            endConnectPos = firstPos;
            endConnectDir = firstDir;
        }
        CurveRoute.CubicBezier lastConnect = CurveRoute.CubicBezier.getCubicBezier(
                endConnectPos,
                endConnectDir,
                con.end().subtract(endConnectPos),
                con.endDir()
        );
        compositeCurve.addSegment(lastConnect);

        return compositeCurve;
    }

    private List<TrackPutInfo> connectTrack(StationPlanner.ConnectionGenInfo con, List<List<Vec3>> pathPoi) {
        List<TrackPutInfo> trackPutInfos = new ArrayList<>();

        // 起点数据
        List<Vec3> startSegment = pathPoi.getFirst();
        Vec3 firstPos = startSegment.getFirst();
        Vec3 firstDir = firstPos.subtract(startSegment.get(1)).multiply(1,0,1).normalize();
        // 终点数据
        List<Vec3> lastSegment = pathPoi.getLast();
        Vec3 lastPos = lastSegment.getLast();
        Vec3 lastDir = lastPos.subtract(lastSegment.get(lastSegment.size()-2)).multiply(1,0,1).normalize();

        trackPutInfos.add(TrackPutInfo.getByDir(
                new BlockPos((int) con.start().x, (int) con.start().y, (int) con.start().z),
                con.startDir(),
                new TrackPutInfo.BezierInfo(
                        con.start(),
                        con.startDir(),
                        firstPos.subtract(con.start()),
                        firstDir
                )
        ));

        // 处理路径
        for (int i = 0; i < pathPoi.size(); i++) {
            // 直线片段
            List<Vec3> segment = pathPoi.get(i);
            for (int j = 0; j < segment.size() - 1; j++) {
                Vec3 pA = segment.get(j);
                Vec3 pB = segment.get(j+1);
                if (pA.y == pB.y) {
                    // 高度相同，使用直线
                    for (int k = 0; k < Math.abs(pA.x - pB.x); k++) {
                        int x = (int) (pA.x + MyMth.getSign(pB.x - pA.x)*k);
                        int z = (int) (pA.z + MyMth.getSign(pB.z - pA.z)*k);
                        trackPutInfos.add(TrackPutInfo.getByDir(
                                new BlockPos(x, (int) pA.y, z),
                                pB.subtract(pA),
                                null
                        ));
                    }
                } else {
                    // 使用贝塞尔曲线
                    Vec3 dir = pB.subtract(pA).multiply(1, 0, 1).normalize();
                    trackPutInfos.add(TrackPutInfo.getByDir(
                            new BlockPos((int) pA.x, (int) pA.y, (int) pA.z),
                            dir,
                            new TrackPutInfo.BezierInfo(
                                    pA,
                                    dir,                                   // 起点切线方向
                                    pB.subtract(pA),
                                    dir.reverse()
                            )
                    ));
                }
            }
            // 转弯贝塞尔曲线段
            if (i < pathPoi.size() - 1) {
                List<Vec3> nextSegment = pathPoi.get(i+1);
                // 使用贝塞尔曲线连接两段路径
                var vecA = new Vec3(segment.getLast().x(), 0, segment.getLast().z()).subtract(
                        new Vec3(segment.getFirst().x(), 0, segment.getFirst().z()));
                var vecB = new Vec3(nextSegment.getFirst().x(), 0, nextSegment.getFirst().z()).subtract(
                        new Vec3(nextSegment.getLast().x(), 0, nextSegment.getLast().z()));
                var prevDirection = vecA.normalize();
                var currentDirection = vecB.normalize();
                var pos = segment.getLast();
                trackPutInfos.add(TrackPutInfo.getByDir(
                        new BlockPos((int) pos.x, (int) pos.y, (int) pos.z),
                        prevDirection,
                        new TrackPutInfo.BezierInfo(
                                pos,
                                prevDirection,                                   // 起点切线方向
                                nextSegment.getFirst().subtract(segment.getLast()),
                                currentDirection
                        )
                ));
            }
        }

        // 车站路线终点连接
        trackPutInfos.add(TrackPutInfo.getByDir(
                new BlockPos((int) con.end().x, (int) con.end().y, (int) con.end().z),
                con.endDir(),
                new TrackPutInfo.BezierInfo(
                        lastPos,
                        lastDir,
                        con.end().subtract(lastPos),
                        con.endDir()
                )
        ));

        return trackPutInfos;
    }


    /**
     * 裁剪路径的起始部分
     * @param path 要裁剪的路径
     * @param trimLength 要裁剪的长度
     */
    private void trimPathStart(List<int[]> path, int trimLength) {
        // 确保不会将路径裁剪得太短
        if (path.size() <= trimLength + 1) {
            return;
        }

        // 移除起始点
        for (int i = 0; i < trimLength && path.size() > 2; i++) {
            path.remove(0);
        }
    }

    /**
     * 裁剪路径的末尾部分
     * @param path 要裁剪的路径
     * @param trimLength 要裁剪的长度
     */
    private void trimPathEnd(List<int[]> path, int trimLength) {
        // 确保不会将路径裁剪得太短
        if (path.size() <= trimLength + 1) {
            return;
        }

        // 移除末尾点
        for (int i = 0; i < trimLength && path.size() > 2; i++) {
            path.remove(path.size() - 1);
        }
    }

    public static class StraightPathFinder {
        // 定义八个方向的向量
        private static final int[][] DIRECTIONS = {
                {0, 1},   // N
                {1, 1},   // NE
                {1, 0},   // E
                {1, -1},  // SE
                {0, -1},  // S
                {-1, -1}, // SW
                {-1, 0},  // W
                {-1, 1}   // NW
        };

        /**
         * 找出所有未拐弯的直线路径
         */
        public static List<List<int[]>> findStraightPaths(List<int[]> path) {
            List<List<int[]>> straightPaths = new ArrayList<>();

            if (path == null || path.size() < 2) {
                return straightPaths;
            }

            int startIndex = 0;

            while (startIndex < path.size() - 1) {
                int[] currentPoint = path.get(startIndex);
                int[] nextPoint = path.get(startIndex + 1);

                // 计算初始方向
                int direction = getDirection(currentPoint, nextPoint);

                // 寻找相同方向的连续点
                List<int[]> straightPath = new ArrayList<>();
                straightPath.add(currentPoint);
                straightPath.add(nextPoint);

                int currentIndex = startIndex + 1;

                while (currentIndex < path.size() - 1) {
                    int[] point1 = path.get(currentIndex);
                    int[] point2 = path.get(currentIndex + 1);

                    int currentDirection = getDirection(point1, point2);

                    if (currentDirection == direction) {
                        straightPath.add(point2);
                        currentIndex++;
                    } else {
                        break;
                    }
                }

                // 只有当路径长度大于等于2时才添加
                if (straightPath.size() >= 2) {
                    straightPaths.add(straightPath);
                }

                // 移动到下一个起点
                startIndex = currentIndex;
            }

            return straightPaths;
        }

        /**
         * 计算两点之间的方向
         */
        private static int getDirection(int[] point1, int[] point2) {
            int dx = point2[0] - point1[0];
            int dz = point2[1] - point1[1];

            // 标准化方向向量（保持方向但长度为1）
            if (dx != 0) dx = dx / Math.abs(dx);
            if (dz != 0) dz = dz / Math.abs(dz);

            // 查找匹配的方向
            for (int i = 0; i < DIRECTIONS.length; i++) {
                if (DIRECTIONS[i][0] == dx && DIRECTIONS[i][1] == dz) {
                    return i;
                }
            }

            return -1; // 不应该发生，因为所有八个方向都覆盖了
        }
    }

    private static List<double[]> adjustmentHeight(List<double[]> path) {
        List<double[]> adjustedPath = new ArrayList<>();
        //连接首末点计算高度基线，求出相对高度。
        if (path.size() < 2)
            return new LinkedList<>();
        double hStart = path.getFirst()[2];
        double hEnd = path.getLast()[2];
        double pNum = path.size() - 1;

        //计算相对高度
        List<double[]> heightList0 = new ArrayList<>(); //坐标、相对高度
        Map<Integer, List<double[]>> heightGroups = new HashMap<>(); //高度索引表
        double distance = 0;
        for (int i = 0; i < path.size(); i++) {
            //计算相对高度
            double[] point = path.get(i);
            double h = point[2] - hStart * ((pNum - i) / pNum) - hEnd * (i / pNum);
            //计算距离
            if (i > 0) {
                double h0 = point[2];
                double h1 = path.get(i-1)[2];
                distance += 1 + Math.abs(h0 - h1);
            }
            //生成点
            double[] p = {point[0], point[1], h, i, distance}; //x,z,高度,索引,距离
            //添加到点表
            heightList0.add(p);
            //添加高度索引表
            int hi = (int) h;
            heightGroups.computeIfAbsent(hi, k -> new ArrayList<>()).add(p);
        }
        // 三角函数
        double sec = Math.sqrt(Math.pow(heightList0.size(), 2) + Math.pow(Math.abs(hStart - hEnd), 2)) / (heightList0.size());

        //削峰填谷
        for (int j = 0; j < heightList0.size(); j++) {
            double[] thisPoint = heightList0.get(j); //获取目前点
            adjustedPath.add(new double[] {thisPoint[0], thisPoint[1], thisPoint[2]});
            int hd = 0; //hd: 下一个点和目前点的高差
            if (j < heightList0.size() - 1) { //下一个点和目前点的高差
                hd = (int)heightList0.get(j+1)[2] - (int)thisPoint[2];
            }
            //同高度，跳过
            if (hd == 0)
                continue;
            double h = thisPoint[2]; //目前点高度
            var group = heightGroups.get((int)h); //获取目前点同高度的点组
            int groupIndex = group.indexOf(thisPoint); //当前点在点组中的索引
            //获取同高度点组中的下一个点
            if (groupIndex < group.size() - 1) { //如果有后继
                double[] nextSameHeightPoint = group.get(groupIndex+1); //同高度的下一个点
                int nextPointIndex = heightList0.indexOf(nextSameHeightPoint); //它的索引
                double dA = thisPoint[4], dB = nextSameHeightPoint[4];
                double iA = thisPoint[3], iB = nextSameHeightPoint[3];
                //可能的桥 可能的隧道
                boolean conditionBridge = hd < 0 && (iB - iA) * 4 * sec < dB - dA;
                boolean conditionTunnel = hd > 0 && (iB - iA) * 3 * sec < dB - dA;
                if (conditionBridge || conditionTunnel) {
                    //调整高度
                    for (int k = j; k < nextPointIndex; k++) {
                        double[] np1 = heightList0.get(k+1);
                        adjustedPath.add(new double[] {np1[0], np1[1], thisPoint[2]});
                    }
                    j = nextPointIndex;
                }
            }
        }
        //最终再将所有点的高度加上基线
        for (int i = 0; i < adjustedPath.size(); i++) {
            double[] p = adjustedPath.get(i);
            p[2] += hStart * ((pNum - i) / pNum) + hEnd * (i / pNum);
        }

        return adjustedPath;
    }

    public record ResultWay(
            CurveRoute.CompositeCurve way,
            List<TrackPutInfo> trackPutInfos
    ){}
}
