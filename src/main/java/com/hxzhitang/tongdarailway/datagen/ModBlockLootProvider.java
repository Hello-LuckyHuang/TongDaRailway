package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import com.simibubi.create.AllBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.Collections;
import java.util.Set;

public class ModBlockLootProvider extends BlockLootSubProvider {
    public static final Set<Block> BLOCK = Set.of(
            ModBlocks.TRACK_SPAWNER.get()
    );

    public ModBlockLootProvider() {
        super(Collections.emptySet(), FeatureFlags.REGISTRY.allFlags());
    }


    @Override
    protected void generate() {
        this.add(ModBlocks.TRACK_SPAWNER.get(), (p_251028_) ->
                this.createSingleItemTable(AllBlocks.TRACK.asItem(), ConstantValue.exactly(4.0F))
        );
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return BLOCK;
    }
}
