package com.hxzhitang.tongdarailway.mixin;

import java.util.concurrent.CompletableFuture;

import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkMap.class)
public abstract class ChunkMapRegionRuleGateMixin {
    @Redirect(
            method = "applyStep(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/status/ChunkStep;apply(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<ChunkAccess> tongDaRailway211$waitForRegionRules(
            ChunkStep step,
            WorldGenContext context,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk
    ) {
        ServerLevel level = context.level();
        if (!tongDaRailway211$shouldGate(step, level, chunk)) {
            return step.apply(context, cache, chunk);
        }

        return RailwayBuilder.getInstance(level.getSeed(), level).prepareIfNeeded(RegionPos.regionPosFromChunkPos(chunk.getPos()))
                .thenCompose(rules -> step.apply(context, cache, chunk));
    }

    @Unique
    private static boolean tongDaRailway211$shouldGate(ChunkStep step, ServerLevel level, ChunkAccess chunk) {
        return tongDaRailway211$isOverworld(level)
                && step.targetStatus() == ChunkStatus.STRUCTURE_STARTS
                && chunk.getPersistedStatus().isBefore(ChunkStatus.STRUCTURE_STARTS);
    }

    @Unique
    private static boolean tongDaRailway211$isOverworld(ServerLevel level) {
        return level.dimension() == Level.OVERWORLD;
    }
}
