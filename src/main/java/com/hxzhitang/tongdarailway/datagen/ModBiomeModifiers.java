package com.hxzhitang.tongdarailway.datagen;


import com.hxzhitang.tongdarailway.Tongdarailway;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext; // 注意这里可能有拼写差异（某些版本是 BootstapContext）
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ForgeBiomeModifiers; // 修改了这里
import net.minecraftforge.registries.ForgeRegistries;

public class ModBiomeModifiers {

    public static final ResourceKey<BiomeModifier> ADD_RAILWAY_AND_STATION = registerKey("add_railway_and_station");

    public static void bootstrap(BootstapContext<BiomeModifier> context) {
        var placedFeatures = context.lookup(Registries.PLACED_FEATURE);
        var biomes = context.lookup(Registries.BIOME);

        context.register(ADD_RAILWAY_AND_STATION,
                // Forge 1.20.1 使用 ForgeBiomeModifiers.AddFeaturesBiomeModifier
                new ForgeBiomeModifiers.AddFeaturesBiomeModifier(
                        biomes.getOrThrow(BiomeTags.IS_OVERWORLD),
                        HolderSet.direct(placedFeatures.getOrThrow(ModPlacements.RAILWAY_PLACED_FEATURE_KEY)),
                        GenerationStep.Decoration.UNDERGROUND_ORES
                )
        );
    }

    private static ResourceKey<BiomeModifier> registerKey(String name) {
        // 1.20.1 必须使用 new ResourceLocation
        return ResourceKey.create(ForgeRegistries.Keys.BIOME_MODIFIERS,
                new ResourceLocation(Tongdarailway.MODID, name));
    }
}