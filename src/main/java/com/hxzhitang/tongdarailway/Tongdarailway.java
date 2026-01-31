package com.hxzhitang.tongdarailway;

import com.hxzhitang.tongdarailway.blocks.ModBlockEntities;
import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import com.hxzhitang.tongdarailway.blocks.TrackSpawnerBlockRenderer;
import com.hxzhitang.tongdarailway.datagen.ModDataGen;
import com.hxzhitang.tongdarailway.event.FeatureRegistry;
import com.mojang.logging.LogUtils;
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

/*
种子 -4301944397657168414
-684 131 1547
缺区块 558 78 1054
结构避让测试: -1031 73 328 | -769 159 5503
车站连接 -1351 160 3034
-3568 76 3507

种子 216126112278392742
-960 63 1424
[x] 对角线段缺失 1839 85 959
[x] 调平不工作/错误的未升降的直线 1341 76 884
[-] 不错的车站 621 64 3478

-6630103939123469904
-560 71 3184
1443514625631274284
-1296 116 1262

-8105556503839263868
529 197 3400
2750 108 2873
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
    public static final int HEIGHT_MAX_INCREMENT = 60;  // 路线生成最大高度相对于海平面的增量

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
