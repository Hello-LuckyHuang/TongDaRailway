package com.hxzhitang.tongdarailway;

import com.hxzhitang.tongdarailway.blocks.ModBlockEntities;
import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import com.hxzhitang.tongdarailway.blocks.TrackSpawnerBlockRenderer;
import com.hxzhitang.tongdarailway.datagen.ModDataGen;
import com.hxzhitang.tongdarailway.event.FeatureRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

/**
 * 问题
 * 1. 标架倒塌
 * 2. 路径重合(基本减轻)
 * 3. 进结构
 * 4. 出口选择错误(避免)
 * 5. 站线不优雅的连接
 */

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Tongdarailway.MODID)
public class Tongdarailway {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "tongdarailway";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // Mod常量
    public static final int CHUNK_GROUP_SIZE = 128;  // 一个路线生成区域的大小
    public static final int HEIGHT_MAX_INCREMENT = 100;  // 路线生成最大高度相对于海平面的增量

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Tongdarailway(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(FeatureRegistry::register);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);

        modEventBus.addListener(ModDataGen::gatherData);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (Tongdarailway) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Some client setup code

            event.enqueueWork(() -> {
                // 注册方块实体渲染器
                BlockEntityRenderers.register(ModBlockEntities.TRACK_SPAWNER.get(), TrackSpawnerBlockRenderer::new);
            });
        }
    }
}
