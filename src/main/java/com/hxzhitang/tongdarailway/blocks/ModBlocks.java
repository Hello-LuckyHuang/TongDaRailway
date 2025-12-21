package com.hxzhitang.tongdarailway.blocks;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

import static com.hxzhitang.tongdarailway.Tongdarailway.MODID;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,MODID);

    public static final RegistryObject<BaseEntityBlock> TRACK_SPAWNER = BLOCKS.register(
            "track_spawner",
            () -> new TrackSpawnerBlock(BlockBehaviour.Properties.of().strength(0.5f).sound(SoundType.METAL).lightLevel(l -> 10).noOcclusion())
    );

    public static final Supplier<Item> TRACK_SPAWNER_ITEM = ITEMS.register("track_spawner", () -> new BlockItem(TRACK_SPAWNER.get(), new Item.Properties()));

    public static void register(IEventBus eventBus){
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
    }
}
