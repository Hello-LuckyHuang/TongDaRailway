package com.hxzhitang.tongdarailway.blocks;

import com.hxzhitang.tongdarailway.Config;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.RoseQuartzLampBlock;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.foundation.utility.Couple; // 1.20.1 中 Couple 的位置
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TrackSpawnerBlockEntity extends BlockEntity {
    private static final int SPAWN_RANGE = 100;
    protected boolean spawnedTrack = false;

    private final List<TrackPutInfo> trackPutInfos = new ArrayList<>();

    public TrackSpawnerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.TRACK_SPAWNER.get(), pos, blockState);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TrackSpawnerBlockEntity entity) {
        if (entity.spawnedTrack || !entity.anyPlayerInRange(level)) {
            return;
        }
        if (!level.isClientSide()) {
            if (!Config.enableTrackSpawner)
                return;
            if (level instanceof ServerLevel world) {
                for (TrackPutInfo track : entity.trackPutInfos) {
                    if (track.bezier() != null) {
                        placeCurveTrack(world, track);
                        // 1.20.1 中服务器任务调度建议直接使用 world.getServer()
                        Objects.requireNonNull(world.getServer()).execute(() -> {
                            placeCurveTrackEntity(world, track);
                        });
                    } else {
                        if (!world.getBlockState(track.pos()).is(AllBlocks.TRACK.get())) { // 1.20.1 中通常用 .get()
                            world.setBlock(track.pos(), AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, track.shape()), 3);
                        }
                    }
                }
            }

            level.destroyBlock(pos, false);
            // 注意 1.20.1 中 RoseQuartzLampBlock.POWERING 属性的引用
            level.setBlock(pos, AllBlocks.ROSE_QUARTZ_LAMP.getDefaultState()
                    .setValue(RoseQuartzLampBlock.POWERING, true), 3);
            entity.spawnedTrack = true;
        }
    }

    public void addTrackPutInfo(List<TrackPutInfo> trackPutInfos) {
        this.trackPutInfos.addAll(trackPutInfos);
    }

    public boolean anyPlayerInRange(Level level) {
        return level.hasNearbyAlivePlayer(this.getBlockPos().getX() + 0.5D, this.getBlockPos().getY() + 0.5D, this.getBlockPos().getZ() + 0.5D, this.getRange());
    }

    protected int getRange() {
        return SPAWN_RANGE;
    }

    // --- 重点修改：NBT 读写方法名和参数在 1.20.1 不同 ---

    @Override
    protected void saveAdditional(CompoundTag nbt) { // 去掉 Provider 参数
        ListTag tracksTag = new ListTag();
        for (TrackPutInfo track : trackPutInfos) {
            tracksTag.add(track.toNBT());
        }
        nbt.put("tracks", tracksTag);
        nbt.putBoolean("Spawned", spawnedTrack);
        super.saveAdditional(nbt);
    }

    @Override
    public void load(CompoundTag nbt) { // 方法名从 loadAdditional 改回 load，去掉 Provider 参数
        super.load(nbt);
        this.spawnedTrack = nbt.getBoolean("Spawned");
        this.trackPutInfos.clear();
        ListTag tracksTag = nbt.getList("tracks", Tag.TAG_COMPOUND);
        for (int i = 0; i < tracksTag.size(); i++) {
            TrackPutInfo track = TrackPutInfo.fromNBT(tracksTag.getCompound(i));
            trackPutInfos.add(track);
        }
    }

    // --- 贝塞尔曲线生成部分 ---

    private static void placeCurveTrack(ServerLevel world, TrackPutInfo track) {
        BlockPos startPos = track.pos();
        BlockState trackState = AllBlocks.TRACK.getDefaultState()
                .setValue(TrackBlock.SHAPE, track.shape())
                .setValue(TrackBlock.HAS_BE, true);
        world.setBlock(startPos, trackState, 3);

        Vec3 offset = track.bezier().endOffset();
        BlockPos endPos = startPos.offset((int) offset.x, (int) offset.y, (int) offset.z);
        BlockState trackState2 = AllBlocks.TRACK.getDefaultState()
                .setValue(TrackBlock.SHAPE, track.endShape())
                .setValue(TrackBlock.HAS_BE, true);
        world.setBlock(endPos, trackState2, 3);
    }

    private static void placeCurveTrackEntity(WorldGenLevel world, TrackPutInfo track) {
        BlockPos startPos = track.pos();
        Vec3 offset = track.bezier().endOffset();
        BlockPos endPos = startPos.offset((int) offset.x, (int) offset.y, (int) offset.z);

        BlockEntity be1 = world.getBlockEntity(startPos);
        BlockEntity be2 = world.getBlockEntity(endPos);

        if (be1 instanceof TrackBlockEntity tbe1 && be2 instanceof TrackBlockEntity tbe2) {
            Vec3 start1 = track.bezier().start().add(getStartVec(track.bezier().startAxis()));
            Vec3 start2 = track.bezier().start().add(track.bezier().endOffset()).add(getStartVec(track.bezier().endAxis()));

            Vec3 axis1 = track.bezier().startAxis();
            Vec3 axis2 = track.bezier().endAxis();

            Vec3 normal1 = new Vec3(0, 1, 0);
            Vec3 normal2 = new Vec3(0, 1, 0);

            BezierConnection connection = new BezierConnection(
                    Couple.create(startPos, endPos),
                    Couple.create(start1, start2),
                    Couple.create(axis1, axis2),
                    Couple.create(normal1, normal2),
                    true,
                    false,
                    TrackMaterial.ANDESITE
            );

            // 在 1.20.1 中手动同步连接
            tbe1.addConnection(connection);
            tbe2.addConnection(connection.secondary());

            // 提醒：可能需要调用通知方块更新的方法
            tbe1.setChanged();
            tbe2.setChanged();
        }
    }

    private static Vec3 getStartVec(Vec3 dir) {
        double offX = (Math.abs(dir.x) < 1e-6) ? 0.5 : (dir.x < 0 ? 0 : 1);
        double offZ = (Math.abs(dir.z) < 1e-6) ? 0.5 : (dir.z < 0 ? 0 : 1);
        return new Vec3(offX, 0, offZ);
    }
}