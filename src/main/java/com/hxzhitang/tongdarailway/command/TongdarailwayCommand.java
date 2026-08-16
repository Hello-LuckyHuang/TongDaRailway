package com.hxzhitang.tongdarailway.command;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.railway.RegionPos;
import com.hxzhitang.tongdarailway.railway.planner.StationPlanner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@EventBusSubscriber(modid = Tongdarailway.MODID)
public final class TongdarailwayCommand {
    private static final int SEARCH_REGION_SIZE = 32;
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TongDaRailway station search");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<UUID> SEARCHING_PLAYERS = ConcurrentHashMap.newKeySet();

    private TongdarailwayCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tongdarailway")
                .then(Commands.literal("searchstation")
                        .executes(context -> startStationSearch(context.getSource()))));
    }

    private static int startStationSearch(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID playerId = player.getUUID();
        if (!SEARCHING_PLAYERS.add(playerId)) {
            source.sendFailure(Component.translatable("commands.tongdarailway.searchstation.already_searching"));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        MinecraftServer server = source.getServer();
        Vec3 playerPosition = player.position();
        RegionPos centerRegion = RegionPos.regionPosFromWorldPos(player.blockPosition().getX(), player.blockPosition().getZ());

        source.sendSuccess(() -> Component.translatable(
                "commands.tongdarailway.searchstation.searching", SEARCH_REGION_SIZE, SEARCH_REGION_SIZE), false);

        SEARCH_EXECUTOR.execute(() -> {
            BlockPos nearestStation = null;
            Throwable searchError = null;
            try {
                nearestStation = findNearestStation(level, playerPosition, centerRegion);
            } catch (Exception exception) {
                searchError = exception;
            }

            BlockPos result = nearestStation;
            Throwable error = searchError;
            server.execute(() -> finishStationSearch(server, playerId, result, error));
        });
        return 1;
    }

    private static BlockPos findNearestStation(ServerLevel level, Vec3 playerPosition, RegionPos centerRegion) {
        int minRegionX = centerRegion.x() - SEARCH_REGION_SIZE / 2;
        int minRegionZ = centerRegion.z() - SEARCH_REGION_SIZE / 2;
        int maxRegionX = minRegionX + SEARCH_REGION_SIZE;
        int maxRegionZ = minRegionZ + SEARCH_REGION_SIZE;
        long seed = level.getSeed();

        ArrayDeque<RegionPos> pendingRegions = new ArrayDeque<>();
        Set<RegionPos> visitedRegions = new HashSet<>();
        pendingRegions.add(centerRegion);
        visitedRegions.add(centerRegion);

        while (!pendingRegions.isEmpty()) {
            RegionPos regionPos = pendingRegions.removeFirst();
            BlockPos nearestStation = findNearestStationInRegion(level, seed, playerPosition, regionPos);
            if (nearestStation != null) {
                return nearestStation;
            }

            enqueueRegion(pendingRegions, visitedRegions, new RegionPos(regionPos.x() - 1, regionPos.z()),
                    minRegionX, minRegionZ, maxRegionX, maxRegionZ);
            enqueueRegion(pendingRegions, visitedRegions, new RegionPos(regionPos.x() + 1, regionPos.z()),
                    minRegionX, minRegionZ, maxRegionX, maxRegionZ);
            enqueueRegion(pendingRegions, visitedRegions, new RegionPos(regionPos.x(), regionPos.z() - 1),
                    minRegionX, minRegionZ, maxRegionX, maxRegionZ);
            enqueueRegion(pendingRegions, visitedRegions, new RegionPos(regionPos.x(), regionPos.z() + 1),
                    minRegionX, minRegionZ, maxRegionX, maxRegionZ);
        }

        return null;
    }

    private static BlockPos findNearestStationInRegion(
            ServerLevel level, long seed, Vec3 playerPosition, RegionPos regionPos) {
        BlockPos nearestStation = null;
        double nearestDistanceSqr = Double.MAX_VALUE;

        var stations = StationPlanner.generateStation(regionPos, level, seed);
        for (var stationAndConnections : stations) {
            StationPlanner.StationGenInfo station = stationAndConnections.getFirst();
            BlockPos stationPos = station.placePos();
            double distanceSqr = stationPos.distToCenterSqr(playerPosition);
            if (distanceSqr < nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearestStation = stationPos;
            }
        }

        return nearestStation;
    }

    private static void enqueueRegion(
            ArrayDeque<RegionPos> pendingRegions,
            Set<RegionPos> visitedRegions,
            RegionPos regionPos,
            int minRegionX,
            int minRegionZ,
            int maxRegionX,
            int maxRegionZ) {
        if (regionPos.x() < minRegionX || regionPos.x() >= maxRegionX
                || regionPos.z() < minRegionZ || regionPos.z() >= maxRegionZ
                || !visitedRegions.add(regionPos)) {
            return;
        }
        pendingRegions.addLast(regionPos);
    }

    private static void finishStationSearch(
            MinecraftServer server, UUID playerId, BlockPos nearestStation, Throwable error) {
        SEARCHING_PLAYERS.remove(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);

        if (error != null) {
            Tongdarailway.LOGGER.error("Failed to search for the nearest station", error);
            if (player != null) {
                player.sendSystemMessage(Component.translatable("commands.tongdarailway.searchstation.failed"));
            }
            return;
        }
        if (player == null) {
            return;
        }
        if (nearestStation == null) {
            player.sendSystemMessage(Component.translatable(
                    "commands.tongdarailway.searchstation.not_found", SEARCH_REGION_SIZE, SEARCH_REGION_SIZE));
            return;
        }

        Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable(
                        "chat.coordinates", nearestStation.getX(), nearestStation.getY(), nearestStation.getZ()))
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + nearestStation.getX() + " "
                                        + nearestStation.getY() + " " + nearestStation.getZ()))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("chat.coordinates.tooltip"))));
        player.sendSystemMessage(Component.translatable(
                "commands.tongdarailway.searchstation.success", coordinates));
    }
}
