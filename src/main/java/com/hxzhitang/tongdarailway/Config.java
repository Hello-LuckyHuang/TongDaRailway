package com.hxzhitang.tongdarailway;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
@EventBusSubscriber(modid = Tongdarailway.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLE_TRACK_SPAWNER = BUILDER
            .comment("Can the track spawner work")
            .define("enableTrackSpawner", true);

    private static final ModConfigSpec.BooleanValue GENERATE_TRACK_SPAWNER = BUILDER
            .comment("Can the track spawner be generated")
            .define("generateTrackSpawner", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enableTrackSpawner;
    public static boolean generateTrackSpawner;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        enableTrackSpawner = ENABLE_TRACK_SPAWNER.get();
        generateTrackSpawner = GENERATE_TRACK_SPAWNER.get();
    }
}
