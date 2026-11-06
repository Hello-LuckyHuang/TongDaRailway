package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RailwayMap;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.*;
import com.mojang.datafixers.util.Pair;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;

// 寻路 生成路径曲线
public class RoutePlanner {
    /** 每次规划独立持有上下文，在管线阶段之间传递数据。 */
    public static final class PlanningContext {
        final RailwayMap railwayMap;
        final WorldGenRegion level;
        final RailwayBuilder builder;
        List<Pair<StationPlanner.StationGenInfo, List<BlockPos>>> stationData = List.of();
        List<StationPlanner.ConnectionGenInfo> connections = List.of();
        private List<PlannedPath> paths = List.of();

        public PlanningContext(RailwayMap railwayMap, WorldGenRegion level, RailwayBuilder builder) {
            this.railwayMap = railwayMap;
            this.level = level;
            this.builder = builder;
        }
    }

    /** 路径始终携带所属连接，避免过滤失败路线后与站点出口错位。 */
    private record PlannedPath(StationPlanner.ConnectionGenInfo connection, List<int[]> points) {
    }

    /** 寻路阶段，为每条已匹配连接生成水平路径。 */
    public static void findPaths(PlanningContext context) {
        List<PlannedPath> paths = new ArrayList<>();
        for (StationPlanner.ConnectionGenInfo connection : context.connections) {
            context.builder.checkPlanningCancelled();
            List<int[]> way = AStarPathfinder.findPath(
                    context.builder,
                    connection.connectStart(),
                    Set.of(connection.connectEnd()),
                    context.railwayMap.regionPos,
                    0,
                    (x, z) -> {
                        int scopeLimit = RailwayMap.scopeLimit(
                                x, z, connection.connectStart(), connection.connectEnd());
                        int heightLimit = context.builder.getHeight(x, z) < context.level.getSeaLevel() + 2
                                ? 100 : 0;
                        return scopeLimit + heightLimit;
                    }
            );
            paths.add(new PlannedPath(connection, way));
        }
        context.paths = paths;
    }

    /** 高度调整阶段，将水平路径转换为满足坡度约束的三维路径。 */
    public static void adjustHeights(PlanningContext context) {
        List<PlannedPath> paths = new ArrayList<>();
        for (PlannedPath path : context.paths) {
            context.builder.checkPlanningCancelled();
            List<int[]> profile = handleHeight(
                    context.builder, path.points(), context.level.getLevel(), path.connection());
            if (profile != null && !profile.isEmpty()) {
                paths.add(new PlannedPath(path.connection(), profile));
            }
        }
        context.paths = paths;
    }

    /** 轨道曲线生成阶段，将调整后的路径转换为曲线和轨道放置信息并按区块保存。 */
    public static void generateTrackCurves(PlanningContext context) {
        for (PlannedPath path : context.paths) {
            context.builder.checkPlanningCancelled();
            ResultWay route = connectTrackNew5(path.points(), path.connection());
            putChunk(context.railwayMap, route);
        }
    }

    /**
     * 测高 处理高度
     * @param path 直行路径(区域内坐标)
     * @param level 服务器世界
     */
    public static List<int[]> handleHeight(RailwayBuilder builder, List<int[]> path, ServerLevel level, StationPlanner.ConnectionGenInfo con) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // 统计高度剖面
        List<int[]> terrainHeights = new ArrayList<>();
        for (int[] point : path) {
            int h = builder.getExactHeight(level, point[0], point[1]);
            terrainHeights.add(new int[] {point[0], point[1], h});
        }

        // 执行路径最优高度规划
        List<int[]> profile = planHeight(terrainHeights, (int) con.start().y, (int) con.end().y, level.getSeaLevel());

        if (profile.isEmpty()) {
            Tongdarailway.LOGGER.warn(
                    "No grade-safe vertical profile from {} to {}",
                    Arrays.toString(con.connectStart()),
                    Arrays.toString(con.connectEnd())
            );
            return null;
        }
        return profile;
    }

    // 人类从AI手里又抢回了一块领地！
    // xdm学蓝桥杯真的有用！
    /**
     * 使用动态规划的高度调整方法。解决思路应该类似于数塔最大值路径
     * 在限制每步坡度的情况下，所有可能路径应该位于一个对侧顶点为起终点高度值的平行四边形包线内
     * 每步只有上升n、水平、下降n共2n+1种走法
     * 每步的损耗根据当前位置和地表高度判定
     * 找到损耗最小的路径走法就是一个比较合理的路线高度剖面
     * @param terrain 路线高度剖面。其中int[2]为在int[0],int[1]这xz坐标处的高度
     * @param start 起点高度
     * @param end 终点高度
     * @param seaLevel 海平面高度，用于区分需要架桥的水域
     * @return 最优路线高度剖面方案
     * */
    private static List<int[]> planHeight(List<int[]> terrain, int start, int end, int seaLevel) {
        if (terrain == null || terrain.isEmpty()) return new ArrayList<>();

        int n = terrain.size();

        // 每步允许的最大高度变化
        final int maxChange = 1;
        // 陆地架桥损耗
        final int bridgeCost = 3;
        // 陆地隧道损耗
        final int tunnelCost = 1;
        // 海洋架桥高度
        final int waterBridgeHeight = 10;
        // 高度变更损耗
        final int slopeCost = 2;

        // 起终点高度差超过最大变化能力，无合法路径
        if (Math.abs((long) end - start) > (long) maxChange * (n - 1))
            return new ArrayList<>();

        // 上一层各高度的最小Cost
        Map<Integer, Integer> dp = new HashMap<>();
        dp.put(start, Math.abs(start - terrain.getFirst()[2]));

        // 记录各层最优路径的前驱高度
        List<Map<Integer, Integer>> parent = new ArrayList<>();

        for (int i = 1; i < n; i++) {

            Map<Integer, Integer> next = new HashMap<>();
            Map<Integer, Integer> prev = new HashMap<>();

            // 当前节点的允许高度包线
            long minH = Math.max(
                    (long) start - (long) maxChange * i,
                    (long) end - (long) maxChange * (n - 1 - i));

            long maxH = Math.min(
                    (long) start + (long) maxChange * i,
                    (long) end + (long) maxChange * (n - 1 - i));

            // 遍历上一层的所有高度
            for (Map.Entry<Integer, Integer> entry : dp.entrySet()) {
                int h = entry.getKey();

                // 每步高度变化范围：-maxChange ~ +maxChange
                for (int d = -maxChange; d <= maxChange; d++) {
                    int height = h + d;

                    // 超出允许包线直接跳过
                    if (height < minH || height > maxH) continue;

                    // 地形偏差Cost + 高度变化Cost
                    int terrainCost = 0;
                    int terrainH = terrain.get(i)[2];
                    int dh = Math.abs(height - terrainH);
                    if (terrainH <= seaLevel) {
                        // 如果此处是水域
                        terrainCost = height > seaLevel + waterBridgeHeight ? 0 : Math.abs(seaLevel+waterBridgeHeight-height) * 500;
                    } else {
                        // 如果此处是陆地
                        if (dh > 5) {
                            if (height > terrainH)
                                terrainCost = dh * bridgeCost;
                            else
                                terrainCost = dh * tunnelCost;
                        }
                    }

                    int cost = entry.getValue()
                            + terrainCost
                            + (d == 0 ? 0 : slopeCost);

                    // 更新当前高度的最小Cost和前驱
                    if (cost < next.getOrDefault(height, Integer.MAX_VALUE)) {
                        next.put(height, cost);
                        prev.put(height, h);
                    }
                }
            }

            dp = next;
            parent.add(prev);
        }

        // 从终点回溯最优路径
        List<int[]> path = new ArrayList<>();
        int h = end;

        for (int i = n - 1; i > 0; i--) {
            int[] point = terrain.get(i).clone();
            point[2] = h;
            path.add(point);
            h = parent.get(i - 1).get(h);
        }

        int[] first = terrain.getFirst().clone();
        first[2] = start;
        path.add(first);

        Collections.reverse(path);

        return path;
    }

    /**
     * 将直线路径段通过三阶贝塞尔曲线平滑连接
     * @param path 路线的端点
     * @return 连接后的复合曲线
     */
    private static ResultWay connectTrackNew5(List<int[]> path, StationPlanner.ConnectionGenInfo con) {
        if (path == null)
            return null;
        // 转换为世界坐标系
        List<Vec3> path1 = new ArrayList<>();
        List<Vec3> dir = new ArrayList<>();
        for (int i = 1; i < path.size()-2; i++) {
            var p0 = path.get(i);
            var p1 = path.get(i+1);
            Vec3 a = new Vec3(p0[0], p0[2], p0[1]);
            Vec3 b = new Vec3(p1[0], p1[2], p1[1]);
            Vec3 c = a.add(b).scale(0.5);
            path1.add(new Vec3((int) c.x, (int) c.y, (int) c.z));
            dir.add(MyMth.get8Dir(b.subtract(a)));
        }

        // 连接线路和车站
        ResultWay result = new ResultWay(new CurveRoute(), new ArrayList<>());

        // 车站起点连接
        Vec3 pA = con.start().add(con.startDir().scale(30));
        path1.addFirst(pA);
        dir.addFirst(con.startDir());

        Vec3 pB = con.end().add(con.endDir().scale(30));
        path1.addLast(pB);
        dir.addLast(con.endDir().reverse());

        result.connectWay2(con.start(), path1.getFirst(), con.startDir(), dir.getFirst().reverse(), true);

        for (int i = 0; i < path1.size()-1; i++) {
            var p0 = path1.get(i);
            var p1 = path1.get(i+1);
            var d0 = dir.get(i);
            var d1 = dir.get(i+1).reverse();
            result.connectWay2(p0, p1, d0, d1, true);
        }

        // 终点车站连接
        result.connectWay2(path1.getLast(), con.end(), dir.getLast(), con.endDir(), true);

        return result;
    }

    /**
     * 添加路径到区块
     * @param route 路径
     */
    private static void putChunk(RailwayMap railwayMap, ResultWay route) {
        if (route == null)
            return;
        for (CurveRoute.CurveSegment segment : route.way().getSegments()) {
            for (Vec3 p : segment.rasterize(16, 5)) {
                int cx = (int) Math.floor(p.x);
                int cz = (int) Math.floor(p.z);
                if (cx >= railwayMap.regionPos.x()*CHUNK_GROUP_SIZE && cx < (railwayMap.regionPos.x()+1)*CHUNK_GROUP_SIZE && cz >= railwayMap.regionPos.z()*CHUNK_GROUP_SIZE && cz < (railwayMap.regionPos.z()+1)*CHUNK_GROUP_SIZE) {
                    railwayMap.routeMap.computeIfAbsent(new ChunkPos(cx, cz), k -> new HashSet<>())
                            .add(route.way());
                }
            }
        }

        for (TrackPutInfo track : route.trackPutInfos()) {
            var pos = track.pos();
            railwayMap.trackMap.computeIfAbsent(new ChunkPos(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16)), k -> new ArrayList<>()).add(track);
        }
    }

    public record ResultWay(
            CurveRoute way,
            List<TrackPutInfo> trackPutInfos
    ) {
        public void connectWay(Vec3 start, Vec3 end, Vec3 startDir, Vec3 endDir, boolean maximiseTurn) {
            boolean success = connectWayNormal(start, end, startDir, endDir, maximiseTurn);
            if (!success) {
                connectWayForce(start, end, startDir, endDir);
                Tongdarailway.LOGGER.warn("The road position cannot be determined, and the line has been forced to connect. {} {}", start, end);
            }
        }

        public void connectWay2(Vec3 start, Vec3 end, Vec3 startDir, Vec3 endDir, boolean maximiseTurn) {
            boolean success = connectWayNormal(start, end, startDir, endDir, maximiseTurn);

            if (!success) {
                int len = (int) (start.distanceTo(end) / 2) - 2;
                Vec3 aPos = (start.add(startDir.scale(len)).add(end.add(endDir.scale(len)))).scale(0.5);
                aPos = new Vec3((int) aPos.x(), (int) aPos.y(), (int) aPos.z());
                Vec3 aDir;
                Vec3 d = aPos.subtract(start).multiply(1, 0, 1).normalize();
                double dot = startDir.dot(d);
                double cross = startDir.x * d.z - startDir.z * d.x;

                if (Mth.equal(dot, 1)) {
                    // 前方 直线
                    connectWay(start, end, startDir, endDir, maximiseTurn);
                    return;
                } else if (dot > 0.78) {
                    // 斜前方 135度钝角
                    aDir = MyMth.rotateAroundY(startDir, cross, 45);
                } else {
                    // 侧前方 90度直角
                    aDir = MyMth.rotateAroundY(startDir, cross, 90);
                }

                // 连接或强行连接
                if(!connectWayNormal(start, aPos, startDir, aDir.reverse(), maximiseTurn)) {
                    connectWayForce(start, aPos, startDir, aDir.reverse());
                    Tongdarailway.LOGGER.warn("The road position cannot be determined, and the line has been forced to connect (-1). {} {}", start, end);
                }
                if(!connectWayNormal(aPos, end, aDir, endDir, maximiseTurn)) {
                    connectWayForce(aPos, end, aDir, endDir);
                    Tongdarailway.LOGGER.warn("The road position cannot be determined, and the line has been forced to connect (-2). {} {}", start, end);
                }
            }
        }

        public boolean connectWayNormal(Vec3 start, Vec3 end, Vec3 startDir, Vec3 endDir, boolean maximiseTurn) {
            BlockPos pos1 = BlockPos.containing(start);
            BlockPos pos2 = BlockPos.containing(end);
            ConnectInfo connect = getConnect(pos1, pos2, startDir, endDir, maximiseTurn);
            if (connect == null)
                return false;

            Vec3 axis1 = connect.startAxis;
            Vec3 axis2 = connect.endAxis;
            Vec3 normedAxis1 = axis1.normalize();
            Vec3 normedAxis2 = axis2.normalize();

            if (connect.skipCurve) {
                addStraightTracks(pos1, axis1, connect.startExtent);
                addStraightTracks(pos2, axis2, connect.endExtent);
                way.addSegment(new CurveRoute.LineSegment(
                        start,
                        end
                ));
                return true;
            }

            // 曲线端点由 Bezier TrackPutInfo 放置，因此这里只添加端点前的延伸直轨。
            addStraightTracks(pos1, axis1, connect.startExtent);
            addStraightTracks(pos2, axis2, connect.endExtent);

            BlockPos curveStartPos = connect.startPos;
            BlockPos curveEndPos = connect.endPos;
            Vec3 curveStart = Vec3.atLowerCornerOf(curveStartPos);
            Vec3 curveEnd = Vec3.atLowerCornerOf(curveEndPos);
            Vec3 endOffset = curveEnd.subtract(curveStart);
            trackPutInfos.add(TrackPutInfo.getByDir(
                    curveStartPos,
                    axis1,
                    new TrackPutInfo.BezierInfo(curveStart, normedAxis1, endOffset, normedAxis2)
            ));

            if (connect.startExtent != 0)
                way.addSegment(new CurveRoute.LineSegment(start, curveStart));
            way.addSegment(CurveRoute.BezierSegment.getCubicBezier(
                    curveStart, normedAxis1, curveEnd.subtract(curveStart), normedAxis2));
            if (connect.endExtent != 0)
                way.addSegment(new CurveRoute.LineSegment(curveEnd, end));
            return true;
        }

        /** 无视 Create 的合法性限制，直接生成一段三阶贝塞尔连接。 */
        public void connectWayForce(Vec3 start, Vec3 end, Vec3 startDir, Vec3 endDir) {
            Vec3 endOffset = end.subtract(start);
            way.addSegment(CurveRoute.BezierSegment.getCubicBezier(start, startDir, endOffset, endDir));
            trackPutInfos.add(TrackPutInfo.getByDir(
                    BlockPos.containing(start),
                    startDir,
                    new TrackPutInfo.BezierInfo(start, startDir, endOffset, endDir)
            ));
        }

        private void addStraightTracks(BlockPos start, Vec3 axis, int count) {
            for (int i = 0; i < count; i++) {
                BlockPos pos = start.offset(BlockPos.containing(axis.scale(i)));
                trackPutInfos.add(TrackPutInfo.getByDir(pos, axis, null));
            }
        }

        private static ConnectInfo getConnect(BlockPos pos1, BlockPos pos2, Vec3 requestedAxis1,
                                              Vec3 requestedAxis2, boolean maximiseTurn) {
            Vec3 axis1 = horizontalTrackAxis(requestedAxis1);
            Vec3 axis2 = horizontalTrackAxis(requestedAxis2);
            if (axis1 == null || axis2 == null || pos1.equals(pos2))
                return null;
            if (pos1.distSqr(pos2) > 256 * 256)
                return null;

            Vec3 normedAxis2 = axis2.normalize();
            Vec3 normedAxis1 = axis1.normalize();
            Vec3 end1 = MyMth.getCurveStart(pos1, axis1);
            Vec3 end2 = MyMth.getCurveStart(pos2, axis2);

            if (axis1.dot(end2.subtract(end1)) < 0) {
                axis1 = axis1.scale(-1);
                normedAxis1 = normedAxis1.scale(-1);
                end1 = MyMth.getCurveStart(pos1, axis1);
            }

            double[] intersect = VecHelper.intersect(end1, end2, normedAxis1, normedAxis2, Direction.Axis.Y);
            boolean parallel = intersect == null;
            boolean skipCurve = false;

            if ((parallel && normedAxis1.dot(normedAxis2) > 0)
                    || (!parallel && (intersect[0] < 0 || intersect[1] < 0))) {
                axis2 = axis2.scale(-1);
                normedAxis2 = normedAxis2.scale(-1);
                end2 = MyMth.getCurveStart(pos2, axis2);
            }

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
                        return null;

                    if (skipCurve) {
                        dist = VecHelper.getCenterOf(pos1)
                                .distanceTo(VecHelper.getCenterOf(pos2));
                        end1Extent = (int) Math.round((dist + 1) / axis1.length());

                    } else {
                        if (!Mth.equal(ascend, 0))
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
            }

            Vec3 offset1 = axis1.scale(end1Extent);
            Vec3 offset2 = axis2.scale(end2Extent);
            BlockPos startPos = pos1.offset(BlockPos.containing(offset1));
            BlockPos endPos = pos2.offset(BlockPos.containing(offset2));

            return new ConnectInfo(
                    startPos,
                    axis1,
                    endPos,
                    axis2,
                    end1Extent,
                    end2Extent,
                    skipCurve
            );
        }

        /** 将归一化方向还原为 Create 的 TrackShape 所使用的整数轨道轴。 */
        private static Vec3 horizontalTrackAxis(Vec3 axis) {
            if (axis == null || !Double.isFinite(axis.x) || !Double.isFinite(axis.y)
                    || !Double.isFinite(axis.z) || !Mth.equal(axis.y, 0))
                return null;

            double x = Math.abs(axis.x);
            double z = Math.abs(axis.z);
            boolean hasX = !Mth.equal(x, 0);
            boolean hasZ = !Mth.equal(z, 0);
            if (!hasX && !hasZ)
                return null;
            if (hasX && hasZ && Math.abs(x - z) > 1e-6)
                return null;

            return new Vec3(hasX ? Math.copySign(1, axis.x) : 0, 0,
                    hasZ ? Math.copySign(1, axis.z) : 0);
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
            BlockPos startPos,
            Vec3 startAxis,
            BlockPos endPos,
            Vec3 endAxis,
            int startExtent,
            int endExtent,
            boolean skipCurve
    ) {
    }
}
