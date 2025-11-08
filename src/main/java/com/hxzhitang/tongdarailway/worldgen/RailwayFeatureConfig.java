package com.hxzhitang.tongdarailway.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

public record RailwayFeatureConfig(int placeHolder) implements FeatureConfiguration {
    public static final Codec<RailwayFeatureConfig> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                    Codec.INT.fieldOf("placeHolder").forGetter(RailwayFeatureConfig::placeHolder)
            ).apply(i, RailwayFeatureConfig::new)
    );
}
