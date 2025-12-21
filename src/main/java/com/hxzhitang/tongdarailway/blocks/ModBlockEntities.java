package com.hxzhitang.tongdarailway.blocks;

import com.hxzhitang.tongdarailway.Tongdarailway;
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;


public class ModBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Tongdarailway.MODID);
    public static final Supplier<BlockEntityType<TrackSpawnerBlockEntity>> TRACK_SPAWNER =
            registerBlockEntity("track_spawner", () -> BlockEntityType.Builder.of(TrackSpawnerBlockEntity::new, ModBlocks.TRACK_SPAWNER.get()));

    public static <T extends BlockEntity> Supplier<BlockEntityType<T>> registerBlockEntity(String key, Supplier<BlockEntityType.Builder<T>> builder) {
        return BLOCK_ENTITIES.register(key, () -> builder.get().build(Util.fetchChoiceType(References.BLOCK_ENTITY, key)));
    }

    public static void register(IEventBus eventBus){
        BLOCK_ENTITIES.register(eventBus);
    }
}
