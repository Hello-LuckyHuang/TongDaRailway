package com.hxzhitang.tongdarailway.blocks;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

import static com.hxzhitang.tongdarailway.Tongdarailway.MODID;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<Block> TRACK_SPAWNER = BLOCKS.registerBlock(
            "track_spawner",
            TrackSpawnerBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(0.5f)
                    .sound(SoundType.COPPER_GRATE)
                    .lightLevel(l -> 10)
                    .noOcclusion()

    );

    public static final Supplier<Item> TRACK_SPAWNER_ITEM = ITEMS.register("track_spawner", () -> new BlockItem(TRACK_SPAWNER.get(), new Item.Properties()));

    public static void register(IEventBus eventBus){
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
    }
}
