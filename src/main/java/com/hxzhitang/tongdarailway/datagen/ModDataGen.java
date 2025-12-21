package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.Tongdarailway;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraftforge.common.data.DatapackBuiltinEntriesProvider;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ModDataGen extends DatapackBuiltinEntriesProvider {
    public static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
            .add(Registries.CONFIGURED_FEATURE, ModFeatures::bootstrap)
            .add(Registries.PLACED_FEATURE, ModPlacements::bootstrap)
            // 注意：1.20.1 中 Forge 的 Biome Modifiers 键名引用
            .add(ForgeRegistries.Keys.BIOME_MODIFIERS, ModBiomeModifiers::bootstrap);


    public ModDataGen(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, BUILDER, Set.of(Tongdarailway.MODID));
    }

    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        ExistingFileHelper efh = event.getExistingFileHelper();

        // 1. 语言文件 (直接 new)
        // 1. 语言文件 EN_US
            generator.addProvider(event.includeClient(), new ModLanguageProviderENUS(packOutput));

// 2. 语言文件 ZH_CN
        generator.addProvider(
                event.includeClient(),
                (DataProvider.Factory<ModLanguageProviderZHCN>) pOutput -> new ModLanguageProviderZHCN(pOutput)
        );

        // 2. 方块状态 (直接 new，不要用 pOutput -> ...)
        generator.addProvider(event.includeClient(), new ModBlockStateProvider(packOutput, Tongdarailway.MODID, efh));

        // 3. 战利品表 (重点：1.20.1 不需要 Factory 强转，且构造函数参数顺序如下)
        generator.addProvider(event.includeServer(), new LootTableProvider(
                packOutput,
                Collections.emptySet(),
                List.of(new LootTableProvider.SubProviderEntry(ModBlockLootProvider::new, LootContextParamSets.BLOCK))
                // 注意：1.20.1 的 LootTableProvider 构造函数结尾不需要 lookupProvider 参数
        ));
    }
}

