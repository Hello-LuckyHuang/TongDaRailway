package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.structure.ModStructureManager;
import com.hxzhitang.tongdarailway.structure.StationTemplate;
import com.hxzhitang.tongdarailway.util.MyMth;
import com.hxzhitang.tongdarailway.util.MyRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static com.hxzhitang.tongdarailway.Tongdarailway.CHUNK_GROUP_SIZE;
import static com.hxzhitang.tongdarailway.Tongdarailway.HEIGHT_MAX_INCREMENT;

// 站点规划 连接规划
public class StationPlanner {
    private final RegionPos regionPos;

    public StationPlanner(RegionPos regionPos) {
        this.regionPos = regionPos;
    }

    // 区域内站点生成
    public static List<StationGenInfo> generateStation(RegionPos regionPos, ServerLevel level, long seed) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState cfg = level.getChunkSource().randomState();

        long regionSeed = seed + regionPos.hashCode();
        List<StationGenInfo> result = new ArrayList<>();
        int[] pos = MyRandom.generatePoints(regionSeed, CHUNK_GROUP_SIZE);
        ChunkPos chunkPos = new ChunkPos(MyMth.chunkPosXFromRegionPos(regionPos, pos[0]), MyMth.chunkPosZFromRegionPos(regionPos, pos[1]));

        int x = chunkPos.getBlockX(0);
        int z = chunkPos.getBlockZ(0);
        int y = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE, level, cfg);

        int h = y;

        // 使得站点的高度在一定区域内最小
        int miny = 2550;
        for (int ix = -2; ix < 3; ix++) {
            for (int iz = -2; iz < 3; iz++) {
                int ox = ix * 32 + x;
                int oz = iz * 32 + z;
                int ty = gen.getBaseHeight(ox, oz, Heightmap.Types.WORLD_SURFACE, level, cfg);
                miny = Math.min(miny, ty);
            }
        }

        if (y - miny > 20)
            h = miny;

        // 确保站点高度在 seaLevel ~ seaLevel + 增量
        h = Math.max(h, level.getSeaLevel());
        h = Math.min(h, level.getSeaLevel() + HEIGHT_MAX_INCREMENT);
        // 根据高度决定生成地上还是地下车站
        StationTemplate station;
        int placeH = h;
        if (h < y - 10) {
            station = ModStructureManager.getRandomUnderGroundStation(regionSeed);
        } else {
            station = ModStructureManager.getRandomNormalStation(regionSeed);
        }
        result.add(new StationGenInfo(station, new BlockPos(x, placeH, z)));

        return result;
    }

    // 路线连接规则生成
    public List<ConnectionGenInfo> generateConnections(ServerLevel level, long seed) {
        List<ConnectionGenInfo> result = new ArrayList<>();

        List<StationGenInfo> thisStations = generateStation(regionPos, level, seed);

        List<StationGenInfo> north = generateStation(new RegionPos(regionPos.x(), regionPos.z()-1), level, seed);
        List<StationGenInfo> south = generateStation(new RegionPos(regionPos.x(), regionPos.z()+1), level, seed);

        List<StationGenInfo> east = generateStation(new RegionPos(regionPos.x()+1, regionPos.z()), level, seed);
        List<StationGenInfo> west = generateStation(new RegionPos(regionPos.x()-1, regionPos.z()), level, seed);

        var thisAssignedExits = assignExits(getExitsPos(thisStations));

        var northAssignedExits = assignExits(getExitsPos(north));
        var southAssignedExits = assignExits(getExitsPos(south));
        var eastAssignedExits = assignExits(getExitsPos(east));
        var westAssignedExits = assignExits(getExitsPos(west));

        var tpos = thisStations.get(0).placePos;
        Tongdarailway.LOGGER.info("====> StationPlanner: {} {} {} {}", tpos.getX(), tpos.getY(), tpos.getZ(), regionPos);


        result.add(ConnectionGenInfo.getConnectionInfo(thisAssignedExits.get(3), eastAssignedExits.get(2), new Vec3(1, 0, 0)));
        result.add(ConnectionGenInfo.getConnectionInfo(westAssignedExits.get(3), thisAssignedExits.get(2), new Vec3(1, 0, 0)));
        result.add(ConnectionGenInfo.getConnectionInfo(northAssignedExits.get(1), thisAssignedExits.get(0), new Vec3(0, 0, 1)));
        result.add(ConnectionGenInfo.getConnectionInfo(thisAssignedExits.get(1), southAssignedExits.get(0), new Vec3(0, 0, 1)));

        return result;
    }

    // 提取所有出口
    private List<StationTemplate.Exit> getExitsPos(List<StationGenInfo> stations) {
        List<StationTemplate.Exit> exits = new ArrayList<>();
        for (StationGenInfo station : stations) {
            BlockPos placePos = station.placePos;
            for (StationTemplate.Exit exit : station.stationTemplate.getExits()) {
                BlockPos offset = exit.exitPos();
                exits.add(new StationTemplate.Exit(placePos.offset(offset), exit.dir()));
            }
        }

        return exits;
    }

    /**
     * 分配东南西北向出口
     * @param exits 出口集合
     * @return 出口集合 顺序: [北, 南, 西, 东, 其他]
     */
    private List<StationTemplate.Exit> assignExits(List<StationTemplate.Exit> exits) {
        if (exits.size() < 4) {
            return exits;
        }

        List<StationTemplate.Exit> copy = new ArrayList<>(exits);

        List<StationTemplate.Exit> result = new ArrayList<>();
        // 对出口的按z坐标进行排序 加偏防止z存在相同
        copy.sort(Comparator.comparingDouble(e -> {
            int z = e.exitPos().getZ();
            Random random = new Random(75_1049 + z);
            double off = random.nextDouble() * 2 - 1;
            return z + off;
        }));

        result.add(copy.removeFirst()); //z最小 北
        result.add(copy.removeLast());  //z最大 南

        // 对出口的按x坐标进行排序 加偏防止x存在相同
        copy.sort(Comparator.comparingDouble(e -> {
            int x = e.exitPos().getX();
            Random random = new Random(75_1052 + x);
            double off = random.nextDouble() * 2 - 1;
            return x + off;
        }));

        result.add(copy.removeFirst());  //x最小 西
        result.add(copy.removeLast());  //x最大 东

        // 剩余的Exit放到result最后
        Set<StationTemplate.Exit> addedExits = new HashSet<>(result);
        for (StationTemplate.Exit exit : exits) {
            if (!addedExits.contains(exit)) {
                result.add(exit);
            }
        }

        return result; // 北 南 西 东
    }

    private static int[] getConnectStart(BlockPos exitPos, Vec3 dir, Vec3 offset, Vec3 exitDir) {
        Vec3 pos = Vec3.atCenterOf(exitPos);
//        Vec3 addOff = offset.normalize().scale(30);
        Vec3 addOff = exitDir.scale(40);
        Vec3 start = pos.add(dir.scale(30).add(addOff));
        // 最后的y尽量确保出站线和出站口在同高度
        return new int[] {(int) start.x, (int) start.z, exitPos.getY()};
    }

    // 站点放置信息(世界坐标系)
    public record StationGenInfo(
            StationTemplate stationTemplate,
            BlockPos placePos
    ) {
        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", stationTemplate.getId());
            tag.putString("type", stationTemplate.getType().name());
            tag.putInt("x", placePos.getX());
            tag.putInt("y", placePos.getY());
            tag.putInt("z", placePos.getZ());
            return tag;
        }

        public static StationGenInfo fromNBT(CompoundTag tag) {
            int id = tag.getInt("id");
            int x = tag.getInt("x");
            int y = tag.getInt("y");
            int z = tag.getInt("z");
            StationTemplate.StationType type = StationTemplate.StationType.valueOf(tag.getString("type"));
            StationTemplate stationTemplate = null;
            switch (type) {
                case NORMAL -> {
                    if (ModStructureManager.normalStation.containsKey(id)) {
                        stationTemplate = ModStructureManager.normalStation.get(id);
                    }
                }
                case UNDER_GROUND -> {
                    if (ModStructureManager.undergroundStation.containsKey(id)) {
                        stationTemplate = ModStructureManager.undergroundStation.get(id);
                    }
                }
            }

            return new StationGenInfo(stationTemplate, new BlockPos(x, y, z));
        }
    }

    /**
     * @param start        起点坐标
     * @param startDir     起点方向
     * @param end          终点坐标
     * @param endDir       终点方向
     * @param connectStart 寻路起点
     * @param connectEnd   寻路终点
     */ // 路线连接信息(世界坐标系)
    public record ConnectionGenInfo(
            Vec3 start,
            Vec3 startDir,
            Vec3 end,
            Vec3 endDir,
            int[] connectStart,
            int[] connectEnd,
            Vec3 exitDir
    ) {
        public static ConnectionGenInfo getConnectionInfo(StationTemplate.Exit A, StationTemplate.Exit B, Vec3 exitDir) {
                Vec3 APos = new Vec3(A.exitPos().getX(), A.exitPos().getY(), A.exitPos().getZ());
                Vec3 BPos = new Vec3(B.exitPos().getX(), B.exitPos().getY(), B.exitPos().getZ());
                return new ConnectionGenInfo(
                        APos,
                        A.dir(),
                        BPos,
                        B.dir(),
                        getConnectStart(A.exitPos(), A.dir(), BPos.subtract(APos), exitDir),
                        getConnectStart(B.exitPos(), B.dir(), APos.subtract(BPos), exitDir.reverse()),
                        exitDir
                );
            }
        }
}
