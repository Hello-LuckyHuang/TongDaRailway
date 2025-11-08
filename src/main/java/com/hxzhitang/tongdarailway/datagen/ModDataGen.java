package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.Tongdarailway;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ModDataGen extends DatapackBuiltinEntriesProvider {
    public static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
            .add(Registries.CONFIGURED_FEATURE, ModFeatures::bootstrap)
            .add(Registries.PLACED_FEATURE, ModPlacements::bootstrap)
            .add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, ModBiomeModifiers::bootstrap);

    public ModDataGen(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, BUILDER, Set.of(Tongdarailway.MODID));
    }

    public static void gatherData(GatherDataEvent event) {
        var lp = event.getLookupProvider();
        ExistingFileHelper efh = event.getExistingFileHelper();
        event.getGenerator().addProvider(event.includeServer(), (DataProvider.Factory<ModDataGen>) pOutput -> new ModDataGen(pOutput, lp));

        // 语言文件
        event.getGenerator().addProvider(
                event.includeClient(),
                (DataProvider.Factory<ModLanguageProviderENUS>) ModLanguageProviderENUS::new
        );
        event.getGenerator().addProvider(
                event.includeClient(),
                (DataProvider.Factory<ModLanguageProviderZHCN>) ModLanguageProviderZHCN::new
        );
        // 方块状态
        event.getGenerator().addProvider(
                event.includeClient(),
                (DataProvider.Factory<ModBlockStateProvider>) pOutput -> new ModBlockStateProvider(pOutput, Tongdarailway.MODID, efh)
        );
        // 战利品表
        event.getGenerator().addProvider(
                event.includeServer(),
                (Factory<ModLootTableProvider>) pOutput -> new ModLootTableProvider(
                        pOutput,
                        Collections.emptySet(),
                        List.of(
                                new LootTableProvider.SubProviderEntry(ModBlockLootProvider::new, LootContextParamSets.BLOCK)
                        ),
                        event.getLookupProvider()
                )
        );
    }
}
