package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.worldgen.RailwayFeatureConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import static com.hxzhitang.tongdarailway.event.FeatureRegistry.RAILWAY_FEATURE;

public class ModFeatures {
    public static final ResourceKey<ConfiguredFeature<?, ?>>RAILWAY_CONFIGURED_FEATURE_KEY = ResourceKey.create(
            Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(Tongdarailway.MODID, "railway_and_station"));
    public static void bootstrap(BootstrapContext<ConfiguredFeature<?, ?>> pContext) {
        pContext.register(RAILWAY_CONFIGURED_FEATURE_KEY,
                new ConfiguredFeature<>(RAILWAY_FEATURE, new RailwayFeatureConfig(0)));
    }
}
