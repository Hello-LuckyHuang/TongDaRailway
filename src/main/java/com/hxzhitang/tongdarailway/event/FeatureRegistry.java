package com.hxzhitang.tongdarailway.event;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.worldgen.RailwayFeatureConfig;
import com.hxzhitang.tongdarailway.worldgen.RailwayFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.neoforge.registries.RegisterEvent;

public class FeatureRegistry {
    public static final RailwayFeature RAILWAY_FEATURE = new RailwayFeature(RailwayFeatureConfig.CODEC);
    public static final ResourceKey<Feature<?>> RAILWAY_FEATURE_KEY = ResourceKey.create(Registries.FEATURE,
            ResourceLocation.fromNamespaceAndPath(Tongdarailway.MODID, "railway_and_station"));

    private FeatureRegistry() {
    }

    public static void register(RegisterEvent event) {
        event.register(Registries.FEATURE,
                helper -> helper.register(RAILWAY_FEATURE_KEY, RAILWAY_FEATURE));
    }
}
