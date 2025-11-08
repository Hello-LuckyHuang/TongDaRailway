package com.hxzhitang.tongdarailway.mixin;

import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.util.MyMth;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.Mixin;
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
        }
    }
}
