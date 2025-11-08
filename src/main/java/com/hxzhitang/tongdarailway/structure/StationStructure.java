package com.hxzhitang.tongdarailway.structure;

import com.hxzhitang.tongdarailway.Tongdarailway;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class StationStructure {
    public enum StationType {
        NORMAL,
        UNDER_GROUND
    }

    private ListTag palette = null;
    private BlockPos size = null;
    private final Map<ChunkPos, CompoundTag> segments = new HashMap<>();
    private int dataVersion = 0;

    private final List<Exit> exits = new ArrayList<>();

    private final int id;

    private final StationType type;

    public List<Exit> getExits() {
        return exits;
    }

    public int getExitCount() {
        return exits.size();
    }

    public StationType getType() {
        return type;
    }

    private StationStructure(ResourceManager resourceManager, String nameSpace, String path, int id, StationType type) {
        this.id = id;
        this.type = type;
        try {
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(nameSpace, path);
            InputStream resourceStream = resourceManager
                    .getResource(location)
                    .orElseThrow()
                    .open();
            try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(resourceStream)))) {
                CompoundTag rootTag = NbtIo.read(stream, NbtAccounter.create(0x20000000L));
                parseStructureNBT(rootTag);
            } catch (Exception e) {
                Tongdarailway.LOGGER.error(e.getMessage());
            }
        } catch (Exception e) {
            Tongdarailway.LOGGER.error(e.getMessage());
        }
    }

    public StationStructure(CompoundTag rootTag, int id, StationType type) {
        this.id = id;
        this.type = type;
        parseStructureNBT(rootTag);
    }

    public void putSegment(WorldGenLevel level, ChunkPos thisChunkPos, BlockPos putPos) {
        // 我真的不知道怎么在世界生成的时候完整的放置大型结构...
        ChunkPos putChunkPos = new ChunkPos(putPos);
        ChunkPos indexChunkPos = new ChunkPos(thisChunkPos.x - putChunkPos.x, thisChunkPos.z - putChunkPos.z);
        BlockPos pos = new BlockPos(thisChunkPos.getBlockX(0), putPos.getY(), thisChunkPos.getBlockZ(0));
        CompoundTag rootTag = segments.get(indexChunkPos);
        if (rootTag == null)
            return;
        StructurePlaceSettings placementSettings = new StructurePlaceSettings();
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), rootTag);
        template.placeInWorld(level, pos, pos, placementSettings, level.getLevel().random, Block.UPDATE_CLIENTS);
    }

    public int getId() {
        return id;
    }

    /**
     * 解析结构 NBT 数据
     */
    private void parseStructureNBT(CompoundTag rootTag) {
        if (rootTag == null) return;
        // 读取基本信息
        ListTag sizeTag = rootTag.getList("size", Tag.TAG_INT);
        int x = sizeTag.getInt(0), y = sizeTag.getInt(1), z = sizeTag.getInt(2);
        size = new BlockPos(x, y, z);
        // 解析调色板
        palette = rootTag.getList("palette", Tag.TAG_COMPOUND);
        // 解析版本
        dataVersion = rootTag.getInt("DataVersion");
        // 解析方块数据
        List<CompoundTag> blocks = parseBlocks(rootTag.getList("blocks", Tag.TAG_COMPOUND));

        for (CompoundTag blockTag : blocks) {
            // 读取位置
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            int bx = posTag.getInt(0);
            int by = posTag.getInt(1);
            int bz = posTag.getInt(2);

            ChunkPos chunkPos = new ChunkPos(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));

            var newNBT = segments.computeIfAbsent(chunkPos, k -> {
                var tag = new CompoundTag();
                ListTag size = new ListTag();
                size.add(IntTag.valueOf(16));
                size.add(IntTag.valueOf(this.size.getY()));
                size.add(IntTag.valueOf(16));
                tag.put("size", size);
                tag.put("entities", new ListTag());
                tag.put("palette",  palette);
                tag.put("blocks", new ListTag());
                tag.putInt("DataVersion", dataVersion);
                return tag;
            });

            CompoundTag tag = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(Math.floorMod(bx, 16)));
            pos.add(IntTag.valueOf(by));
            pos.add(IntTag.valueOf(Math.floorMod(bz, 16)));
            tag.put("pos", pos);
            tag.putInt("state", blockTag.getInt("state"));
            ListTag bTag = (ListTag) (newNBT.get("blocks"));
            if (bTag == null)
                continue;
            bTag.add(tag);
            newNBT.put("blocks", bTag);
        }
    }

    private List<CompoundTag> parseBlocks(ListTag blocksTag) {
        List<CompoundTag> blocks = new ArrayList<>();
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag blockTag = blocksTag.getCompound(i);
            int index = blockTag.getInt("state");
            CompoundTag tag = (CompoundTag) palette.get(index);
            if (tag.getString("Name").equals("minecraft:jigsaw")) {
                // 解析拼图方块标记的出口参数
                CompoundTag properties = (CompoundTag) tag.get("Properties");
                if (properties == null)
                    continue;
                String orientation = properties.getString("orientation");
                ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
                int bx = posTag.getInt(0);
                int by = posTag.getInt(1);
                int bz = posTag.getInt(2);
                BlockPos offsetPos = new BlockPos(bx, by, bz);
                switch (orientation) {
                    case "north_up" ->
                            exits.add(new Exit(offsetPos, new Vec3(0, 0, -1)));
                    case "south_up" ->
                            exits.add(new Exit(offsetPos, new Vec3(0, 0, 1)));
                    case "east_up" ->
                            exits.add(new Exit(offsetPos, new Vec3(1, 0, 0)));
                    case "west_up" ->
                            exits.add(new Exit(offsetPos, new Vec3(-1, 0, 0)));
                }
            } else {
                // 其他方块
                blocks.add(blockTag);
            }
        }

        return blocks;
    }

    public record Exit(
            BlockPos exitPos,
            Vec3 dir
    ) {
    }
}
