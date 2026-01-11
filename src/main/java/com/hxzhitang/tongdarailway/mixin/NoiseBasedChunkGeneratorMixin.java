package com.hxzhitang.tongdarailway.mixin;

import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RailwayMap;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.railway.planner.StationPlanner;
import com.hxzhitang.tongdarailway.structure.StationTemplate;
import com.hxzhitang.tongdarailway.util.MyMth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "buildSurface", at = @At("HEAD"))
    public void surfaceStart(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk, CallbackInfo ci) {
        var dimensionType = level.dimensionType();
        // 只有主世界生成路
        if (dimensionType.effectsLocation().toString().equals("minecraft:overworld")) {
            RegionPos regionPos = MyMth.regionPosFromChunkPos(chunk.getPos());

            RailwayBuilder railwayBuilder = RailwayBuilder.getInstance(level.getSeed(), level);
            railwayBuilder.generateRailway(regionPos);


            // 生成车站托盘
            RailwayMap railwayMap = railwayBuilder.regionRailways.get(regionPos);
            if (railwayMap != null) {
                for (StationPlanner.StationGenInfo stationPlace : railwayMap.stations) {
                    var station = stationPlace.stationTemplate();
                    if (station == null) continue;
                    if (station.type == StationTemplate.StationType.UNDER_GROUND) continue;
                    var pos = stationPlace.placePos();
                    var center = pos.getCenter();

                    if (station.getTrayBoundChunks(center).contains(chunk.getPos())) {
                        tongDaRailway211$placeTray(chunk.getPos(), center, station, chunk);
                    }
                }
            }
        }
    }

    @Unique
    private static void tongDaRailway211$placeTray(ChunkPos cPos, Vec3 center, StationTemplate station, ChunkAccess chunk) {
        Vec3 placeCenter = center.add(0, -3, 0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int oy = -5; oy < 6; oy++) {
                    int y = oy + (int) placeCenter.y;
                    var p = new Vec3(cPos.x*16+x, y, cPos.z*16+z);
                    double d = station.dis2Tray(placeCenter, p);
                    if (d < 3)
                        chunk.setBlockState(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), true);
                }
            }
        }
    }
}
