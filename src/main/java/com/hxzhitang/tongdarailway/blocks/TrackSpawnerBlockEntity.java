package com.hxzhitang.tongdarailway.blocks;

import com.hxzhitang.tongdarailway.Config;
import com.hxzhitang.tongdarailway.structure.TrackPutInfo;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.RoseQuartzLampBlock;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
    private static final int SPAWN_RANGE = 50;
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
//                        //高差过大拒绝生成
//                        if (Math.abs(track.bezier().endOffset().y) > 15)
//                            continue;
                        placeCurveTrack(world, track);
                        Objects.requireNonNull(level.getServer()).execute(() -> {
                            placeCurveTrackEntity(world, track);
                        });
                    } else {
                        if (!world.getBlockState(track.pos()).is(AllBlocks.TRACK)) {
                            world.setBlock(track.pos(), AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, track.shape()), 3);
                        }
                    }
                }
            }

            level.destroyBlock(pos, false);
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

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider p_331864_) {
        ListTag tracksTag = new ListTag();
        for (TrackPutInfo track : trackPutInfos) {
            tracksTag.add(track.toNBT());
        }
        nbt.put("tracks", tracksTag);
        super.saveAdditional(nbt, p_331864_);
    }

    @Override
    public void loadAdditional(CompoundTag nbt, HolderLookup.Provider p_335164_) {
        super.loadAdditional(nbt, p_335164_);
        ListTag tracksTag = nbt.getList("tracks", Tag.TAG_COMPOUND);
        for (int i = 0; i < tracksTag.size(); i++) {
            TrackPutInfo track = TrackPutInfo.fromNBT(tracksTag.getCompound(i));
            trackPutInfos.add(track);
        }
    }

    private static void placeCurveTrack(ServerLevel world, TrackPutInfo track) {
        // 1. 放置第一段直轨
        BlockPos startPos = track.pos();
        BlockState trackState = AllBlocks.TRACK.getDefaultState()
                .setValue(TrackBlock.SHAPE, track.shape())
                .setValue(TrackBlock.HAS_BE, true);
        world.setBlock(startPos, trackState, 3);

        // 2. 放置第二段直轨
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

        // 3. 创建贝塞尔连接
        TrackBlockEntity tbe1 = (TrackBlockEntity) world.getBlockEntity(startPos);
        TrackBlockEntity tbe2 = (TrackBlockEntity) world.getBlockEntity(endPos);

        if (tbe1 != null && tbe2 != null) {
            // 定义起点和终点
            Vec3 start1 = track.bezier().start().add(getStartVec(track.bezier().startAxis()));
            Vec3 start2 = track.bezier().start().add(track.bezier().endOffset()).add(getStartVec(track.bezier().endAxis()));

            // 定义轴向(切线方向)
            Vec3 axis1 = track.bezier().startAxis();  // X轴正方向
            Vec3 axis2 = track.bezier().endAxis();  // Z轴正方向

            // 定义法线(向上)
            Vec3 normal1 = new Vec3(0, 1, 0);
            Vec3 normal2 = new Vec3(0, 1, 0);

            // 创建贝塞尔连接
            BezierConnection connection = new BezierConnection(
                    Couple.create(startPos, endPos),
                    Couple.create(start1, start2),
                    Couple.create(axis1, axis2),
                    Couple.create(normal1, normal2),
                    true,  // teToTe
                    false, // hasGirder
                    TrackMaterial.ANDESITE
            );
            tbe1.setLevel(world.getLevel());
            tbe2.setLevel(world.getLevel());

            // 添加连接到两端的方块实体
            tbe1.addConnection(connection);
            tbe2.addConnection(connection.secondary());
        }
    }

    private static Vec3 getStartVec(Vec3 dir) {
        double offX;
        if (Math.abs(dir.x) < 1e-6) {
            offX = 0.5;
        } else if (dir.x < 0) {
            offX = 0;
        } else {
            offX = 1;
        }

        double offZ;
        if (Math.abs(dir.z) < 1e-6) {
            offZ = 0.5;
        } else if (dir.z < 0) {
            offZ = 0;
        } else {
            offZ = 1;
        }

        return new Vec3(offX, 0, offZ);
    }
}
