package com.hxzhitang.tongdarailway;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
@EventBusSubscriber(modid = Tongdarailway.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.DoubleValue AREA_GEN_RAILWAY_PROBABILITY = BUILDER
            .comment("The probability of generating a railway in a region. 0 means never generate, 1 means always generate.")
            .defineInRange("areaGenerationRailwayProbability", 0.6, 0, 1);

    private static final ModConfigSpec.BooleanValue ENABLE_TRACK_SPAWNER = BUILDER
            .comment("Can the track spawner work")
            .define("enableTrackSpawner", true);

    private static final ModConfigSpec.BooleanValue GENERATE_TRACK_SPAWNER = BUILDER
            .comment("Can the track spawner be generated")
            .define("generateTrackSpawner", true);

    private static final ModConfigSpec.BooleanValue PLACE_TRACKS_USING_TRACK_SPAWNER = BUILDER
            .comment("Use the track spawner,(true) or place rails during world generation.(false)")
            .define("placeTracksUsingTrackSpawner", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static double areaGenRailwayProbability;
    public static boolean enableTrackSpawner;
    public static boolean generateTrackSpawner;
    public static boolean useTrackSpawnerPlaceTrack;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        areaGenRailwayProbability = AREA_GEN_RAILWAY_PROBABILITY.get();
        enableTrackSpawner = ENABLE_TRACK_SPAWNER.get();
        generateTrackSpawner = GENERATE_TRACK_SPAWNER.get();
        useTrackSpawnerPlaceTrack = PLACE_TRACKS_USING_TRACK_SPAWNER.get();
    }
}
