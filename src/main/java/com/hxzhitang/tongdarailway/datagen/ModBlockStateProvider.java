package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.client.model.generators.ModelFile;
import net.minecraftforge.common.data.ExistingFileHelper;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, String modid, ExistingFileHelper exFileHelper) {
        super(output, modid, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        ModelFile trackSpawnerModel = models()
                .cube("track_spawner",
                        modLoc("block/track_spawner_bottom"),
                        modLoc("block/track_spawner_top"),
                        modLoc("block/track_spawner"),
                        modLoc("block/track_spawner"),
                        modLoc("block/track_spawner"),
                        modLoc("block/track_spawner")
                )
                .texture("particle", modLoc("block/track_spawner"))
                .renderType("cutout");

        this.simpleBlockWithItem(ModBlocks.TRACK_SPAWNER.get(), trackSpawnerModel);
    }
}
