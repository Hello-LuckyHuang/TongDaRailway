package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.*;
import com.simibubi.create.content.trains.track.ITrackBlock;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
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
        return connectTrackNew(handledHeightWay, connectionGenInfo);
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
     * 将直线路径段通过三阶贝塞尔曲线平滑连接
     * @param path 路线的端点
     * @return 连接后的复合曲线
     */
    private ResultWay connectTrackNew(List<int[]> path, StationPlanner.ConnectionGenInfo con) {
        // 转换为世界坐标系
        List<Vec3> path0 = new ArrayList<>();

        for (int i = 2; i < path.size() - 2; i++) {
            int[] point = path.get(i);
            path0.add(MyMth.inRegionPos2WorldPos(
                    regionPos,
                    new Vec3(point[0], point[2], point[1])
                            .multiply(16.0/samplingNum, 1, 16.0/samplingNum)
            ));
        }

        // 连接线路和车站
        Vec3 first = path0.getFirst();
        Vec3 last = path0.getLast();
        Vec3 firstDir = first.subtract(path0.get(1)).normalize();
        Vec3 lastDir = last.subtract(path0.get(path0.size()-2)).normalize();

        ResultWay result = new ResultWay(new CurveRoute.CompositeCurve(), new ArrayList<>());

        // 车站起点连接
        Vec3 pA = con.start().add(con.startDir().scale(30)).add(con.exitDir().scale(30));
        if (con.startDir().dot(con.exitDir()) > 0.999) {
            result.addLine(con.start(), pA);
        } else {
            result.addBezier(con.start(), con.startDir(), pA.subtract(con.start()), con.exitDir().reverse());
        }
        result.addBezier(pA, con.exitDir(), first.subtract(pA), firstDir);


        // 从第二个点开始向下找到倒数第二个点
        a:
        for (int i = 0; i < path0.size() - 1; i++) {
            int j = Math.min(8, path0.size() - 1 - i);
            // 检查前方一定范围内是否有可以合法连接的点
            Vec3 startPos = path0.get(i);
            Vec3 startDir;
            if (i == 0)
                startDir = path0.get(i+1).subtract(path0.get(i)).multiply(1,0,1).normalize();
            else
                startDir = path0.get(i).subtract(path0.get(i-1)).multiply(1,0,1).normalize();

            // 最小检查到前面两个点
            while (j >= 2) {
                Vec3 endPos0 = path0.get(i + j);
                Vec3 endDir0 = path0.get(i+j).subtract(path0.get(i+j-1)).multiply(1,0,1).normalize();

                if (isValidTrackPlacement(startPos, startDir, endPos0, endDir0.reverse())) {
                    if (startPos.y == endPos0.y && startDir.dot(endDir0) > 0.9999 && startDir.dot(endPos0.subtract(startPos).normalize()) > 0.9999) {
                        result.addLine(startPos, endPos0);
                    } else {
                        result.addBezier(startPos, startDir, endPos0.subtract(startPos), endDir0.reverse());
                    }
                    i += j-1;
                    continue a;
                }
                j--;
            }
            // 未检查到，只能直接强行连接
            Vec3 endPos = path0.get(i + 1);
            Vec3 endDir = path0.get(i + 1).subtract(path0.get(i)).multiply(1,0,1).normalize();
            if (startPos.y == endPos.y && startDir.dot(endDir) > 0.9999 && startDir.dot(endPos.subtract(startPos).normalize()) > 0.9999) {
                result.addLine(startPos, endPos);
            } else {
                result.addBezier(startPos, startDir, endPos.subtract(startPos), endDir.reverse());
            }
        }

        // 终点车站连接
        Vec3 pB = con.end().add(con.endDir().scale(30)).add(con.exitDir().reverse().scale(30));
        result.addBezier(last, lastDir, pB.subtract(last), con.exitDir().reverse());
        if (con.endDir().dot(con.exitDir().reverse()) > 0.999) {
            result.addLine(pB, con.end());
        } else {
            result.addBezier(pB, con.exitDir(), con.end().subtract(pB), con.endDir());
        }

        return result;
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

    /**
     * 验证轨道放置的合法性
     * @param startPos 起点坐标
     * @param startAxis 起点切线向量
     * @param endPos 终点坐标
     * @param endAxis 终点切线向量
     * @return true表示合法,false表示非法
     */
    public static boolean isValidTrackPlacement(
            Vec3 startPos,
            Vec3 startAxis,
            Vec3 endPos,
            Vec3 endAxis
    ) {
        // 1. 检查距离限制 (默认最大100格)
        double maxLength = 100.0;
        if (startPos.distanceToSqr(endPos) > maxLength * maxLength) {
            return false; // 距离过远
        }

        // 2. 检查是否为同一点
        if (startPos.equals(endPos)) {
            return false; // 不能连接到自己
        }

        // 3. 归一化轴向量
        Vec3 normedAxis1 = startAxis.normalize();
        Vec3 normedAxis2 = endAxis.normalize();

        // 4. 检查是否平行
        double[] intersect = VecHelper.intersect(startPos, endPos, normedAxis1, normedAxis2, Direction.Axis.Y);
        boolean parallel = intersect == null;

        // 5. 检查垂直连接 (平行且方向相同)
        if (parallel && normedAxis1.dot(normedAxis2) > 0) {
            return false; // 不能垂直连接
        }

        // 6. 检查转弯角度
        if (!parallel) {
            double a1 = Mth.atan2(normedAxis2.z, normedAxis2.x);
            double a2 = Mth.atan2(normedAxis1.z, normedAxis1.x);
            double angle = a1 - a2;
            float absAngle = Math.abs(AngleHelper.deg(angle));

            // 只能转弯最多90度
            if (absAngle < 60 || absAngle > 300) {
                return false;
            }

            // 检查最小转弯半径
            intersect = VecHelper.intersect(startPos, endPos, normedAxis1, normedAxis2, Direction.Axis.Y);
            if (intersect == null || intersect[0] < 0 || intersect[1] < 0) {
                return false; // 转弯过于尖锐
            }

            double dist1 = Math.abs(intersect[0]);
            double dist2 = Math.abs(intersect[1]);
            double turnSize = Math.min(dist1, dist2) - 0.1;

            boolean ninety = (absAngle + 0.25f) % 90 < 1;
            double minTurnSize = ninety ? 7 : 3.25;

            if (turnSize < minTurnSize) {
                return false; // 转弯半径过小
            }
        }

        // 7. 检查S型弯曲
        if (parallel) {
            Vec3 cross2 = normedAxis2.cross(new Vec3(0, 1, 0));
            double[] sTest = VecHelper.intersect(startPos, endPos, normedAxis1, cross2, Direction.Axis.Y);

            if (sTest != null && sTest[0] < 0) {
                return false; // 不能垂直连接
            }

            if (sTest != null && !Mth.equal(Math.abs(sTest[1]), 0)) {
                double t = Math.abs(sTest[0]);
                double u = Math.abs(sTest[1]);
                double targetT = u <= 1 ? 3 : u * 2;

                if (t < targetT) {
                    return false; // S型弯曲过于尖锐
                }
            }
        }

        // 所有检查通过
        return true;
    }

    public record ResultWay(
            CurveRoute.CompositeCurve way,
            List<TrackPutInfo> trackPutInfos
    ) {
        public void addLine(Vec3 start, Vec3 end) {
            way.addSegment(new CurveRoute.LineSegment(start, end));
            int n = Math.max((int) Math.abs(start.x - end.x), (int) Math.abs(start.z - end.z));
            for (int k = 0; k <= n; k++) {
                int x = (int) (start.x + MyMth.getSign(end.x - start.x)*k);
                int z = (int) (start.z + MyMth.getSign(end.z - start.z)*k);
                trackPutInfos.add(TrackPutInfo.getByDir(
                        new BlockPos(x, (int) start.y, z),
                        end.subtract(start),
                        null
                ));
            }
        }

        public void addBezier(Vec3 start, Vec3 startDir, Vec3 endOffset, Vec3 endDir) {
            if (Math.abs(startDir.dot(endDir)) > 0.9999 && startDir.dot(endOffset.normalize()) > 0.9999) {
                way.addSegment(new CurveRoute.LineSegment(start, start.add(endOffset)));
            } else {
                way.addSegment(CurveRoute.CubicBezier.getCubicBezier(start, startDir, endOffset, endDir));
            }
            trackPutInfos.add(TrackPutInfo.getByDir(
                    new BlockPos((int) start.x, (int) start.y, (int) start.z),
                    startDir,
                    new TrackPutInfo.BezierInfo(
                            start,
                            startDir,
                            endOffset,
                            endDir
                    )
            ));
        }
    }
}
