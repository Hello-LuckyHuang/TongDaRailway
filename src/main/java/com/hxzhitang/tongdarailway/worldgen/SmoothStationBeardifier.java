package com.hxzhitang.tongdarailway.worldgen;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

import java.util.List;

/**
 * Adds a wide, continuously smoothed terrain transition around large station footprints while retaining
 * every contribution produced by the vanilla Beardifier.
 */
public final class SmoothStationBeardifier extends Beardifier {
    public static final int HORIZONTAL_BLEND_DISTANCE = 32;

    private static final double VERTICAL_BLEND_DISTANCE = 16.0;
    private static final double VERTICAL_TRANSITION_WIDTH = 0.5;
    private static final double MAX_CONTRIBUTION = 0.8;

    private final Beardifier vanillaBeardifier;
    private final List<StationInfluence> stationInfluences;

    public SmoothStationBeardifier(Beardifier vanillaBeardifier, List<StationInfluence> stationInfluences) {
        super(
                new ObjectArrayList<Beardifier.Rigid>().iterator(),
                new ObjectArrayList<JigsawJunction>().iterator()
        );
        this.vanillaBeardifier = vanillaBeardifier;
        this.stationInfluences = List.copyOf(stationInfluences);
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double contribution = vanillaBeardifier.compute(context);
        for (StationInfluence stationInfluence : stationInfluences) {
            contribution += stationInfluence.compute(context);
        }
        return contribution;
    }

    public record StationInfluence(BoundingBox box, int groundY) {
        private double compute(DensityFunction.FunctionContext context) {
            int horizontalX = Math.max(0, Math.max(box.minX() - context.blockX(), context.blockX() - box.maxX()));
            int horizontalZ = Math.max(0, Math.max(box.minZ() - context.blockZ(), context.blockZ() - box.maxZ()));
            double horizontalDistance = Math.hypot(horizontalX, horizontalZ);
            if (horizontalDistance >= HORIZONTAL_BLEND_DISTANCE) {
                return 0.0;
            }

            // The half-block offset places the density transition between the foundation surface and the air above it.
            double verticalDistance = groundY - context.blockY() - 0.5;
            double absoluteVerticalDistance = Math.abs(verticalDistance);
            if (absoluteVerticalDistance >= VERTICAL_BLEND_DISTANCE) {
                return 0.0;
            }

            double horizontalWeight = smootherStep(1.0 - horizontalDistance / HORIZONTAL_BLEND_DISTANCE);
            double verticalWeight = smootherStep(1.0 - absoluteVerticalDistance / VERTICAL_BLEND_DISTANCE);
            double signedGroundShape = Math.tanh(verticalDistance / VERTICAL_TRANSITION_WIDTH);
            return MAX_CONTRIBUTION * horizontalWeight * verticalWeight * signedGroundShape;
        }
    }

    private static double smootherStep(double value) {
        double clamped = Mth.clamp(value, 0.0, 1.0);
        return clamped * clamped * clamped * (clamped * (clamped * 6.0 - 15.0) + 10.0);
    }
}
