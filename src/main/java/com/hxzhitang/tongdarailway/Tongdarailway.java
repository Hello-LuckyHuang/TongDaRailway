package com.hxzhitang.tongdarailway;

import com.hxzhitang.tongdarailway.blocks.ModBlockEntities;
import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import com.hxzhitang.tongdarailway.blocks.TrackSpawnerBlockRenderer;
import com.hxzhitang.tongdarailway.datagen.ModDataGen;
import com.hxzhitang.tongdarailway.event.FeatureRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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
    public Tongdarailway() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 2. 注册配置 (Forge 1.20.1 方式)
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 3. 注册其他内容 (如 Registrate)
        // REGISTRATE.registerEventListeners(modEventBus);

        // 4. 注册 Forge 全局事件总线 (如 PlayerTick, LevelTick)
        MinecraftForge.EVENT_BUS.register(this);
        modEventBus.addListener(FeatureRegistry::register);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);

        modEventBus.addListener(ModDataGen::gatherData);

    }

    private void commonSetup(final FMLCommonSetupEvent event) {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
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
