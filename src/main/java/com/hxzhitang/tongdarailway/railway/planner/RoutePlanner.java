package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.*;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Collectors;

import static com.hxzhitang.tongdarailway.Tongdarailway.HEIGHT_MAX_INCREMENT;

// 寻路 生成路径曲线
public class RoutePlanner {
    /**
     * 规划路径
     * @param way 路线图
     */
    public ResultWay getWay(RailwayBuilder builder, List<int[]> way, StationPlanner.ConnectionGenInfo connectionGenInfo, ServerLevel level) {
        List<int[]> handledHeightWay = handleHeight(builder, way, level, connectionGenInfo);
        return connectTrackNew3(handledHeightWay, connectionGenInfo);
    }

    /**
     * 测高 处理高度
     * @param path 直行路径(区域内坐标)
     * @param level 服务器世界
     */
    public List<int[]> handleHeight(RailwayBuilder builder, List<int[]> path, ServerLevel level, StationPlanner.ConnectionGenInfo con) {
        List<double[]> adPath = new LinkedList<>();
        int seaLevel = level.getSeaLevel();
        // 测高
        for (int[] p : path) {
            int h = builder.getHeight(p[0], p[1]);
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
        int framed2 = ((max - min) / 2) + 20;

        if (adPath.size() > framed2*2 && framed2*2 >= 3) {
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
    private ResultWay connectTrackNew3(List<int[]> path, StationPlanner.ConnectionGenInfo con) {
        // 转换为世界坐标系
        List<Vec3> path0 = new ArrayList<>();

        for (int i = 0; i < path.size() - 2; i++) {
            int[] point = path.get(i);
            path0.add(new Vec3(point[0], point[2], point[1]));
        }

        List<Vec3> path1 = new ArrayList<>();
        for (int i = 0; i < path0.size()-12; i+=6) {
            path1.add(path0.get(i));
        }
        path1.addLast(path0.getLast());

        // 连接线路和车站
//        Vec3 last = path1.getLast();

        ResultWay result = new ResultWay(new CurveRoute(), new ArrayList<>());

        // 车站起点连接
        Vec3 pA = con.start().add(con.startDir().scale(30)).add(con.exitDir().scale(25));
//        pA = new Vec3(pA.x(), (int) path1.getFirst().y, pA.z());
        result.addBezier(con.start(), con.startDir(), pA.subtract(con.start()), con.exitDir().reverse());

        Vec3 pB = con.end().add(con.endDir().scale(30)).add(con.exitDir().reverse().scale(25));
//        pB = new Vec3(pB.x(), (int) last.y, pB.z());

        path1.addFirst(pA);
        path1.addLast(pB);

        Vec3 startDir = con.exitDir();
        Vec3 endDir;
        for (int i = 0; i < path1.size() - 1; i++) {
            Vec3 start = path1.get(i);
            Vec3 end = path1.get(i + 1);

            endDir = MyMth.get8Dir(end.subtract(start)).reverse();
            if (i == path1.size() - 2) // 处理和终点的连接
                endDir = con.exitDir().reverse();

            if (ResultWay.getConnect(BlockPos.containing(start.multiply(1,0,1)), BlockPos.containing(end.multiply(1,0,1)), startDir, endDir, false) != null) {
                Vec3 dir = end.subtract(start).multiply(1, 0, 1).normalize();
                double dot2 = startDir.dot(endDir.reverse());
                double dot1 = startDir.dot(dir);
                if (Mth.equal(dot1, 1) && Mth.equal(dot2, 1))
                    result.addBezier(start, startDir, end.subtract(start), endDir);
                else
                    result.connectWay(start, end, startDir, endDir, start.y == end.y);
            } else {
                int len = (int) (start.distanceTo(end) / 2) - 2;
                Vec3 aPos = (start.add(startDir.scale(len)).add(end.add(endDir.scale(len)))).scale(0.5);
                aPos = new Vec3((int) aPos.x(), (int) aPos.y(), (int) aPos.z());
                Vec3 aDir;
                Vec3 d = aPos.subtract(start).multiply(1, 0, 1).normalize();
                double dot = startDir.dot(d);
                double cross = startDir.x * d.z - startDir.z * d.x;
                boolean maximiseTurn = start.y == end.y;

                if (Mth.equal(dot, 1)) {
                    // 前方 直线
                    aDir = startDir;
                    result.addBezier(start, startDir, aPos.subtract(start), aDir.reverse());
                    result.addBezier(aPos, aDir, end.subtract(aPos), endDir);
                    continue;
                } else if (dot > 0.78) {
                    // 斜前方 135度钝角
                    aDir = MyMth.rotateAroundY(startDir, cross, 45);
                } else {
                    // 侧前方 90度直角
                    aDir = MyMth.rotateAroundY(startDir, cross, 90);
                }
                result.connectWay(start, aPos, startDir, aDir.reverse(), maximiseTurn);
                result.connectWay(aPos, end, aDir, endDir, maximiseTurn);
            }
            startDir = endDir.reverse();
        }

        // 终点车站连接
        result.addBezier(pB, con.exitDir(), con.end().subtract(pB), con.endDir());

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

    public record ResultWay(
            CurveRoute way,
            List<TrackPutInfo> trackPutInfos
    ) {
        public void connectWay(Vec3 start, Vec3 end, Vec3 startDir, Vec3 endDir, boolean maximiseTurn) {
            int h0 = (int) start.y;
            int h1 = (int) ((start.y + end.y) / 3);
            int h2 = (int) (2*(start.y + end.y) / 3);
            int h3 = (int) end.y;
            Vec3 s = new Vec3(start.x, h1, start.z);
            Vec3 e = new Vec3(end.x, h1, end.z);
            var connect = getConnect(BlockPos.containing(s), BlockPos.containing(e), startDir, endDir, maximiseTurn);
            if (connect != null) {
                if (connect.startExtent < 3) {
                    h1 = h0;
                }
                if (connect.endExtent < 3) {
                    h2 = h3;
                }

                Vec3 lStart = new Vec3(start.x, h0, start.z);
                Vec3 bStart = new Vec3(connect.startPos.x, h1, connect.startPos.z);
                Vec3 bEnd = new Vec3(connect.endPos.x, h2, connect.endPos.z);
                Vec3 lEnd = new Vec3(end.x, h3, end.z);

                if (connect.startExtent != 0) {
                    addBezier(lStart, startDir, bStart.subtract(lStart), startDir.reverse());
                }
                addBezier(bStart, startDir, bEnd.subtract(bStart), endDir);
                if (connect.endExtent != 0) {
                    addBezier(bEnd, endDir.reverse(), lEnd.subtract(bEnd), endDir);
                }
            } else {
                // 强行连接
                addBezier(start, startDir, end.subtract(start), endDir);
                Tongdarailway.LOGGER.warn("The road position cannot be determined, and the line has been forced to connect. {} {}", start, end);
            }
        }

        private static ConnectInfo getConnect(BlockPos pos1, BlockPos pos2, Vec3 axis1, Vec3 axis2, boolean maximiseTurn) {
            Vec3 normal2 = new Vec3(0, 1, 0);
            Vec3 normedAxis2 = axis2.normalize();

            Vec3 normedAxis1 = axis1.normalize();
            Vec3 normal1 = new Vec3(0, 1, 0);

            Vec3 end1 = MyMth.getCurveStart(pos1, axis1);
            Vec3 end2 = MyMth.getCurveStart(pos2, axis2);

            double[] intersect = VecHelper.intersect(end1, end2, normedAxis1, normedAxis2, Direction.Axis.Y);
            boolean parallel = intersect == null;
            boolean skipCurve = false;

            Vec3 cross2 = normedAxis2.cross(new Vec3(0, 1, 0));

            double a1 = Mth.atan2(normedAxis2.z, normedAxis2.x);
            double a2 = Mth.atan2(normedAxis1.z, normedAxis1.x);
            double angle = a1 - a2;
            double ascend = end2.subtract(end1).y;
            double absAscend = Math.abs(ascend);


            int end1Extent = 0;
            int end2Extent = 0;

            // S curve or Straight
            double dist = 0;

            if (parallel) {
                double[] sTest = VecHelper.intersect(end1, end2, normedAxis1, cross2, Direction.Axis.Y);
                if (sTest != null) {
                    double t = Math.abs(sTest[0]);
                    double u = Math.abs(sTest[1]);

                    skipCurve = Mth.equal(u, 0);

                    if (!skipCurve && sTest[0] < 0)
                        return new ConnectInfo(
                                new Vec3(pos1.getX(), pos1.getY(), pos1.getZ()),
                                axis1,
                                new Vec3(pos2.getX(), pos2.getY(), pos2.getZ()),
                                axis2,
                                end1Extent,
                                end2Extent
                        );

                    if (skipCurve) {
                        dist = VecHelper.getCenterOf(pos1)
                                .distanceTo(VecHelper.getCenterOf(pos2));
                        end1Extent = (int) Math.round((dist + 1) / axis1.length());

                    } else {
                        if (!Mth.equal(ascend, 0) || normedAxis1.y != 0)
                            return null;

                        double targetT = u <= 1 ? 3 : u * 2;

                        if (t < targetT)
                            return null;

                        // This is for standardising s curve sizes
                        if (t > targetT) {
                            int correction = (int) ((t - targetT) / axis1.length());
                            end1Extent = maximiseTurn ? 0 : correction / 2 + (correction % 2);
                            end2Extent = maximiseTurn ? 0 : correction / 2;
                        }
                    }
                }
            }

            // Straight ascend
            if (skipCurve && !Mth.equal(ascend, 0)) {
                int hDistance = end1Extent;
                if (axis1.y == 0 || !Mth.equal(absAscend + 1, dist / axis1.length())) {

                    if (axis1.y != 0 && axis1.y == -axis2.y)
                        return null;

                    end1Extent = 0;
                    double minHDistance = Math.max(absAscend < 4 ? absAscend * 4 : absAscend * 3, 6) / axis1.length();
                    if (hDistance < minHDistance)
                        return null;
                    if (hDistance > minHDistance) {
                        int correction = (int) (hDistance - minHDistance);
                        end1Extent = maximiseTurn ? 0 : correction / 2 + (correction % 2);
                        end2Extent = maximiseTurn ? 0 : correction / 2;
                    }

                    skipCurve = false;
                }
            }

            // Turn
            if (!parallel) {
                float absAngle = Math.abs(AngleHelper.deg(angle));
                if (absAngle < 60 || absAngle > 300)
                    return null;

                intersect = VecHelper.intersect(end1, end2, normedAxis1, normedAxis2, Direction.Axis.Y);
                double dist1 = Math.abs(intersect[0]);
                double dist2 = Math.abs(intersect[1]);
                float ex1 = 0;
                float ex2 = 0;

                if (dist1 > dist2)
                    ex1 = (float) ((dist1 - dist2) / axis1.length());
                if (dist2 > dist1)
                    ex2 = (float) ((dist2 - dist1) / axis2.length());

                double turnSize = Math.min(dist1, dist2) - .1d;
                boolean ninety = (absAngle + .25f) % 90 < 1;

                if (intersect[0] < 0 || intersect[1] < 0)
                    return null;

                double minTurnSize = ninety ? 7 : 3.25;
                double turnSizeToFitAscend =
                        minTurnSize + (ninety ? Math.max(0, absAscend - 3) * 2f : Math.max(0, absAscend - 1.5f) * 1.5f);

                if (turnSize < minTurnSize)
                    return null;
                if (turnSize < turnSizeToFitAscend)
                    return null;

                // This is for standardising curve sizes
                if (!maximiseTurn) {
                    ex1 += (float) ((turnSize - turnSizeToFitAscend) / axis1.length());
                    ex2 += (float) ((turnSize - turnSizeToFitAscend) / axis2.length());
                }
                end1Extent = Mth.floor(ex1);
                end2Extent = Mth.floor(ex2);
                turnSize = turnSizeToFitAscend;
            }

            Vec3 offset1 = axis1.scale(end1Extent);
            Vec3 offset2 = axis2.scale(end2Extent);
            BlockPos startPos = pos1.offset(MyMth.myCeil(offset1));
            BlockPos endPos = pos2.offset(MyMth.myCeil(offset2));

            return new ConnectInfo(
                    new Vec3(startPos.getX(), startPos.getY(), startPos.getZ()),
                    axis1,
                    new Vec3(endPos.getX(), endPos.getY(), endPos.getZ()),
                    axis2,
                    end1Extent,
                    end2Extent
            );
        }
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
            if (Mth.equal(Math.abs(startDir.dot(endDir)), 1) && Mth.equal(startDir.dot(endOffset.normalize()), 1) && endOffset.y == 0) {
                Vec3 end = start.add(endOffset);
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
            } else {
                way.addSegment(CurveRoute.BezierSegment.getCubicBezier(start, startDir, endOffset, endDir));
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

    private record ConnectInfo(
            Vec3 startPos,
            Vec3 startAxis,
            Vec3 endPos,
            Vec3 endAxis,
            int startExtent,
            int endExtent
    ) {
    }
}
