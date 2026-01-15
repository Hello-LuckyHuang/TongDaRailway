package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.hxzhitang.tongdarailway.util.*;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            Arrays.fill(ints, 50000);
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

    // 获得十字形四向区域的结构损耗图
    public int[][] getStructureCostMap(WorldGenRegion level) {
        int[][] structureMap = new int[CHUNK_GROUP_SIZE*samplingNum*3][CHUNK_GROUP_SIZE*samplingNum*3];
        for (int[] ints : structureMap) {
            Arrays.fill(ints, 50000);
        }
        for (int i = -1; i < 2; i++) {
            for (int j = -1; j < 2; j++) {
                if (Math.abs(i) == 1 && Math.abs(j) == 1)
                    continue;
                RegionPos rPos = new RegionPos(regionPos.x() + i, regionPos.z() + j);
                RailwayBuilder builder = RailwayBuilder.getInstance(level.getSeed());
                int[][] map;
                if (builder != null) {
                    map = builder.regionStructureMap
                            .computeIfAbsent(rPos, k -> getStructureMap(level,rPos));
                } else {
                    map = getStructureMap(level,rPos);
                }
                for (int x = 0; x < map.length; x++) {
                    for (int z = 0; z < map[0].length; z++) {
                        int picX = (i+1)*CHUNK_GROUP_SIZE*samplingNum+x;
                        int picZ = (j+1)*CHUNK_GROUP_SIZE*samplingNum+z;
                        structureMap[picX][picZ] = map[x][z];
                    }
                }
            }
        }

        return structureMap;
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
//            Tongdarailway.LOGGER.info(" Build HeightMap time: {}ms", endTime - startTime);
        } catch (InterruptedException e) {
            Tongdarailway.LOGGER.error(e.getMessage());
        } finally {
            sampler.shutdown();
        }

        int[][] heightMap = sampler.generateImage(CHUNK_GROUP_SIZE*samplingNum, CHUNK_GROUP_SIZE*samplingNum);

        return heightMap;
    }

    private int[][] getStructureMap(WorldGenRegion level, RegionPos regionPos) {
        // 计算遗迹
        var serverLevel = level.getLevel();
        var registryAccess = level.registryAccess();
        var chunkGeneratorStructureState = serverLevel.getChunkSource().getGeneratorState();
        var structureManager = serverLevel.structureManager();
        var structureFeatureManager = serverLevel.getStructureManager();

        var dimensionType = level.dimensionType();
        LevelHeightAccessor levelHeightAccessor = LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
        var biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);

        List<BlockPos> structurePos = new ArrayList<>();

        try(ExecutorService executor = Executors.newFixedThreadPool(16)) {
            // 创建线程池
            CountDownLatch latch = new CountDownLatch(CHUNK_GROUP_SIZE * CHUNK_GROUP_SIZE);
            for (int gx = 0; gx < CHUNK_GROUP_SIZE; gx++) {
                for (int gz = 0; gz < CHUNK_GROUP_SIZE; gz++) {
                    int finalGx = gx;
                    int finalGz = gz;
                    executor.execute(() -> {
                        try {
                            // 执行任务
                            var protoChunk = new ProtoChunk(new ChunkPos(regionPos.x() * CHUNK_GROUP_SIZE + finalGx, regionPos.z() * CHUNK_GROUP_SIZE + finalGz), UpgradeData.EMPTY, levelHeightAccessor, biomeRegistry, null);
                            //计算和连接遗迹
                            serverLevel.getChunkSource().getGenerator().createStructures(registryAccess, chunkGeneratorStructureState, structureManager, protoChunk, structureFeatureManager);
                            var res = protoChunk.getAllStarts();
//                            var structureRegistry = registryAccess.registryOrThrow(Registries.STRUCTURE);
                            res.forEach((key, value) -> {
//                                String structureName = Objects.requireNonNull(structureRegistry.getKey(key)).toString();
                                BlockPos pos = new BlockPos(protoChunk.getPos().x * 16, 0, protoChunk.getPos().z * 16);
                                structurePos.add(pos);
                            });
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            // 等待所有任务完成
            latch.await();
            // 关闭线程池
            executor.shutdown();
        } catch (InterruptedException e) {
            Tongdarailway.LOGGER.error("Search Feature Err: ", e);
        }

        int[][] costMap = new int[CHUNK_GROUP_SIZE*samplingNum][CHUNK_GROUP_SIZE*samplingNum];
        for (BlockPos pos : structurePos) {
            int[] p = new int[] {
                    (pos.getX() - regionPos.x()*CHUNK_GROUP_SIZE*16)*samplingNum/16,
                    (pos.getZ() - regionPos.z()*CHUNK_GROUP_SIZE*16)*samplingNum/16
            };
            for (int x = -5*samplingNum; x < 5*samplingNum; x++) {
                for (int z = -5*samplingNum; z < 5*samplingNum; z++) {
                    int px = p[0]+x;
                    int pz = p[1]+z;
                    if (px > 0 && px < costMap.length && pz > 0 && pz < costMap[0].length)
                        costMap[px][pz] = 500;
                }
            }
        }

        return costMap;
    }

    /**
     * 规划路径
     * @param way 路线图
     */
    public ResultWay getWay(List<int[]> way, int[][] costMap, StationPlanner.ConnectionGenInfo connectionGenInfo, ServerLevel level) {
        List<int[]> handledHeightWay = handleHeight(way, level, costMap, connectionGenInfo);
        // 结果转为中心图坐标系
        handledHeightWay = handledHeightWay.stream().map(AStarPathfinder::pic2RegionPos).toList();
        return connectTrackNew2(handledHeightWay, connectionGenInfo);
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
    private ResultWay connectTrackNewTest(List<int[]> path, StationPlanner.ConnectionGenInfo con) {
        int scale = 8/samplingNum;

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

        ResultWay result = new ResultWay(new CurveRoute(), new ArrayList<>());

        // 连接线路和车站
//        Vec3 last = path1.getLast();

        // 车站起点连接
        Vec3 pA = con.start().add(con.startDir().scale(30)).add(con.exitDir().scale(30));
        if (con.startDir().dot(con.exitDir()) > 0.999) {
            result.addLine(con.start(), pA);
        } else {
            result.connectWay(con.start(), pA, con.startDir(), con.exitDir().reverse(), true);
        }


        // 连出站...



        for (int i = 1; i < path0.size() - 1; i++) {
            Vec3 bakPos = path0.get(i-1);
            Vec3 thisPos = path0.get(i);
            Vec3 nextPos = path0.get(i+1);

            Vec3 nextDir = nextPos.subtract(thisPos).multiply(1,0,1).normalize();
            Vec3 backDir = thisPos.subtract(bakPos).multiply(1,0,1).normalize();

            int sdx = MyMth.splitFunc(backDir.x) * scale;
            int sdz = MyMth.splitFunc(backDir.z) * scale;

            int edx = MyMth.splitFunc(nextDir.x) * scale;
            int edz = MyMth.splitFunc(nextDir.z) * scale;

            Vec3 cons = new Vec3(thisPos.x-sdx, thisPos.y, thisPos.z-sdz);
            Vec3 cone = new Vec3(thisPos.x+edx, nextPos.y, thisPos.z+edz);

            result.addBezier(cons, backDir, cone.subtract(cons), nextDir.reverse());
        }

//        Vec3 startPos = path0.getFirst();
//        Vec3 startDir = con.exitDir();

//        int len = 0;
//        int turn = 0;
//        for (int i = 1; i < path0.size() - 1; i++) {
//            Vec3 thisPos = path0.get(i);
//
//            Vec3 bakDir = thisPos.subtract(path0.get(i-1)).multiply(1,0,1).normalize();
//            Vec3 forDir = path0.get(i+1).subtract(thisPos).multiply(1,0,1).normalize();
//
//            if (bakDir.dot(forDir) < 0.9999) {
//                turn++;
//            }
//            len++;
//
//            // 连接一次
//            if (turn > 0 || len > 5) {
//                int sdx = MyMth.splitFunc(startDir.x) * scale;
//                int sdz = MyMth.splitFunc(startDir.z) * scale;
//
//                int edx = MyMth.splitFunc(forDir.x) * scale;
//                int edz = MyMth.splitFunc(forDir.z) * scale;
//
//                Vec3 cons = new Vec3(startPos.x+sdx, startPos.y, startPos.z+sdz);
//                Vec3 cone = new Vec3(thisPos.x+edx, thisPos.y, thisPos.z+edz);
//
//                result.addBezier(cons, startDir, cone.subtract(cons), forDir.reverse());
//
//                len = 0;
//                turn = 0;
//
//                startPos = thisPos;
//                startDir = forDir;
//            }
//        }

        // 终点车站连接
        Vec3 pB = con.end().add(con.endDir().scale(30)).add(con.exitDir().reverse().scale(30));
//        result.connectWay(last, pB, startDir, con.exitDir().reverse(), false);
        if (con.endDir().dot(con.exitDir().reverse()) > 0.999) {
            result.addLine(pB, con.end());
        } else {
            result.connectWay(pB, con.end(), con.exitDir(), con.endDir(), true);
        }

        return result;
    }

    /**
     * 将直线路径段通过三阶贝塞尔曲线平滑连接
     * @param path 路线的端点
     * @return 连接后的复合曲线
     */
    private ResultWay connectTrackNew2(List<int[]> path, StationPlanner.ConnectionGenInfo con) {
        // 转换为世界坐标系
        List<Vec3> path0 = new ArrayList<>();

        for (int i = 0; i < path.size() - 2; i++) {
            int[] point = path.get(i);
            path0.add(MyMth.inRegionPos2WorldPos(
                    regionPos,
                    new Vec3(point[0], point[2], point[1])
                            .multiply(16.0/samplingNum, 1, 16.0/samplingNum)
            ));
        }

        List<Vec3> path1 = new ArrayList<>();
        for (int i = 0; i < path0.size()-10; i+=6) {
            path1.add(path0.get(i));
        }

        Vec3 a1 = path1.getLast();
        Vec3 b1 = path0.getLast();
        Vec3 c1 = a1.add(b1.subtract(a1).scale(0.5));
        path1.addLast(new Vec3((int) c1.x(), (int) c1.y(), (int) c1.z()));

        // 连接线路和车站
        Vec3 last = path1.getLast();

        ResultWay result = new ResultWay(new CurveRoute(), new ArrayList<>());

        // 车站起点连接
        Vec3 pA = con.start().add(con.startDir().scale(30)).add(con.exitDir().scale(25));
        pA = new Vec3(pA.x(), (int)((con.start().y+path1.getFirst().y)/2), pA.z());
        result.addBezier(con.start(), con.startDir(), pA.subtract(con.start()), con.exitDir().reverse());

        path1.addFirst(pA);

        int size = path1.size();
        int i = 0;
        Vec3 startDir = con.exitDir();
        while (i < size - 1) {
            Vec3 start = path1.get(i);
            Vec3 end = path1.get(i + 1);
            Vec3 endDir;

            Vec3 nextDir = end.subtract(start).multiply(1,0,1).normalize();

            // 根据夹角计算出方向
            double dot = startDir.dot(nextDir);
            double cross = startDir.x * nextDir.z - startDir.z * nextDir.x;

            boolean maximiseTurn = start.y == end.y;
            if (dot > 0.9999) {
                // 前方 直线
                endDir = startDir.reverse();
                result.addBezier(start, startDir, end.subtract(start), endDir);
                i++;
                continue;
            } else if (dot > 0.975) {
                // 前方 平行
                endDir = startDir.reverse();
            } else if (dot > 0.75) {
                // 斜前方 135度钝角
                endDir = MyMth.rotateAroundY(startDir, cross, 45).reverse();
            } else if (dot > 0.165) {
                // 侧前方 90度直角
                endDir = MyMth.rotateAroundY(startDir, cross, 90).reverse();
            } else {
                // 侧方/侧后方 直角+下一轮
                endDir = MyMth.rotateAroundY(startDir, cross, 90).reverse();

                Vec3 d1 = new Vec3(MyMth.splitFunc(startDir.x), 0, MyMth.splitFunc(startDir.z));
                Vec3 d2 = new Vec3(MyMth.splitFunc(endDir.x), 0, MyMth.splitFunc(endDir.z)).reverse();
                Vec3 newPoint = start.add(d1.scale(8)).add(d2.scale(8));
                result.addBezier(start, startDir, newPoint.subtract(start), endDir);

                path1.add(i+1, newPoint);

                startDir = endDir.reverse();
                i++;
                size++;
                continue;
            }

            result.connectWay(start, end, startDir, endDir, maximiseTurn);

            startDir = endDir.reverse();
            i++;
        }

        // 终点车站连接
        Vec3 pB = con.end().add(con.endDir().scale(30)).add(con.exitDir().reverse().scale(25));
        pB = new Vec3(pB.x(), (int)((con.end().y+last.y)/2), pB.z());
        result.connectWay(last, pB, startDir, con.exitDir().reverse(), false);

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
            int h = (int) ((start.y + end.y) / 2);
            Vec3 s = new Vec3(start.x, h, start.z);
            Vec3 e = new Vec3(end.x, h, end.z);
            var connect = getConnect(BlockPos.containing(s), BlockPos.containing(e), startDir, endDir, maximiseTurn);
            if (connect != null) {
                if (connect.startExtent < 8)
                    h = (int) start.y;
                else if (connect.endExtent < 8)
                    h = (int) end.y;

                Vec3 conStart = new Vec3(connect.startPos.x, h, connect.startPos.z);
                Vec3 conEnd = new Vec3(connect.endPos.x, h, connect.endPos.z);
                if (connect.startExtent != 0) {
                    addBezier(start, startDir, conStart.subtract(start), startDir.reverse());
                }
                addBezier(conStart, startDir, conEnd.subtract(conStart), endDir);
                if (connect.endExtent != 0) {
                    addBezier(conEnd, endDir.reverse(), end.subtract(conEnd), endDir);
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
            if (Math.abs(startDir.dot(endDir)) > 0.9999 && startDir.dot(endOffset.normalize()) > 0.9999 && endOffset.y == 0) {
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
