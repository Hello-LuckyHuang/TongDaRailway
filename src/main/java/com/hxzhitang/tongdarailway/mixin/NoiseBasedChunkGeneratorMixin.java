package com.hxzhitang.tongdarailway.mixin;

import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RailwayMap;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.structure.StationTemplate;
import com.hxzhitang.tongdarailway.worldgen.SmoothStationBeardifier;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @WrapOperation(
            method = "createNoiseChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/Beardifier;forStructuresInChunk(Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/levelgen/Beardifier;"
            )
    )
    private Beardifier tongDaRailway211$addStationBeardifier(
            StructureManager structureManager,
            ChunkPos chunkPos,
            Operation<Beardifier> original
    ) {
        Beardifier beardifier = original.call(structureManager, chunkPos);
        LevelAccessor levelAccessor = ((StructureManagerAccessor) structureManager).tongDaRailway211$getLevel();
        if (!(levelAccessor instanceof WorldGenRegion level)
                || !level.dimensionType().effectsLocation().equals(Level.OVERWORLD.location())) {
            return beardifier;
        }

        RegionPos regionPos = RegionPos.regionPosFromChunkPos(chunkPos);
        RailwayBuilder railwayBuilder = RailwayBuilder.getInstance(level.getSeed(), level);
        railwayBuilder.generateRailway(regionPos);

        RailwayMap railwayMap = railwayBuilder.regionRailways.get(regionPos);
        if (railwayMap == null) {
            return beardifier;
        }

        int blendDistance = SmoothStationBeardifier.HORIZONTAL_BLEND_DISTANCE;
        int minChunkX = chunkPos.getMinBlockX() - blendDistance;
        int minChunkZ = chunkPos.getMinBlockZ() - blendDistance;
        int maxChunkX = chunkPos.getMaxBlockX() + blendDistance;
        int maxChunkZ = chunkPos.getMaxBlockZ() + blendDistance;
        List<SmoothStationBeardifier.StationInfluence> stationInfluences = new ArrayList<>();

        for (var stationPlace : railwayMap.stations) {
            StationTemplate station = stationPlace.stationTemplate();
            if (station == null || station.getType() == StationTemplate.StationType.UNDER_GROUND) {
                continue;
            }

            BoundingBox box = station.getBeardifierBox(stationPlace.placePos());
            if (box.intersects(minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                stationInfluences.add(new SmoothStationBeardifier.StationInfluence(
                        box,
                        box.minY() + station.getBeardifierGroundLevelDelta()
                ));
            }
        }

        return stationInfluences.isEmpty()
                ? beardifier
                : new SmoothStationBeardifier(beardifier, stationInfluences);
    }
}
