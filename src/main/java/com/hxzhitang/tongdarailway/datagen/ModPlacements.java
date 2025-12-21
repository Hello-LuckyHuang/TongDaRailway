package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.Tongdarailway;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import static com.hxzhitang.tongdarailway.datagen.ModFeatures.RAILWAY_CONFIGURED_FEATURE_KEY;

public class ModPlacements {
    public static final ResourceKey<PlacedFeature> RAILWAY_PLACED_FEATURE_KEY = ResourceKey.create(
            Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath(Tongdarailway.MODID, "railway_and_station"));

    public static void bootstrap(BootstapContext<PlacedFeature> pContext) {
        HolderGetter<ConfiguredFeature<?, ?>> lookup = pContext.lookup(Registries.CONFIGURED_FEATURE);
        pContext.register(RAILWAY_PLACED_FEATURE_KEY,
                new PlacedFeature(lookup.getOrThrow(RAILWAY_CONFIGURED_FEATURE_KEY), java.util.List.of(InSquarePlacement.spread())));
    }
}
