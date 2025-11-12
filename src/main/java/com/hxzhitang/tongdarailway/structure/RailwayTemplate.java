package com.hxzhitang.tongdarailway.structure;

import com.hxzhitang.tongdarailway.Tongdarailway;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

public class RailwayTemplate {
    private VoxelGrid voxelGrid;
    private int roadbedHeight = 0;

    private int dataVersion;

    public RailwayTemplate(Path path) {
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(path, StandardOpenOption.READ))))) {
            CompoundTag rootTag = NbtIo.read(stream, NbtAccounter.create(0x20000000L));
            voxelGrid = parseStructureNBT(rootTag);
        } catch (Exception e) {
            Tongdarailway.LOGGER.error(e.getMessage());
        }
    }

    public RailwayTemplate(CompoundTag nbt) {
        voxelGrid = parseStructureNBT(nbt);
    }

    public int getWidth() {return voxelGrid.getWidth();}
    public int getHeight() {return voxelGrid.getHeight();}
    public int getDepth() {return voxelGrid.getDepth();}

    public int getUpperBound() {
        return voxelGrid.getHeight() - roadbedHeight + 1;
    }

    public int getLowerBound() {
        return -(roadbedHeight + 1);
    }

    // 坐标系原点在z方向中心方块的方块坐标处
    public BlockState getBlockState(double x, double y, double z) {
        // 映射回原始体素坐标
        // 原始X坐标由曲线参数决定
        int originalX = (int) Math.floor(x) % getWidth();

        // 原始Y和Z坐标由局部坐标决定（考虑网格中心）
        int originalY = (int) Math.round(y + roadbedHeight + 1);  // 从路面高度计y坐标
        int originalZ = (int) Math.floor(z + getDepth() / 2.0);

        return voxelGrid.getBlockState(originalX, originalY, originalZ);
    }

    /**
     * 解析结构 NBT 数据
     */
    private VoxelGrid parseStructureNBT(CompoundTag rootTag) {
        if (rootTag == null) return null;

        dataVersion = rootTag.getInt("DataVersion");

        // 读取基本信息
        ListTag sizeTag = rootTag.getList("size", Tag.TAG_INT);
        int x = sizeTag.getInt(0), y = sizeTag.getInt(1), z = sizeTag.getInt(2);
        BlockPos size = new BlockPos(x, y, z);

        // 解析调色板
        List<BlockState> palette = parsePalette(rootTag.getList("palette", Tag.TAG_COMPOUND));

        // 寻找角落结构方块
        int cornerStructureBlockIndex = -1;
        ListTag paletteTag = rootTag.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag blockTag = paletteTag.getCompound(i);
            if (blockTag.contains("Name") && blockTag.getString("Name").equals("minecraft:structure_block")) {
                if (blockTag.contains("Properties", Tag.TAG_COMPOUND)) {
                    CompoundTag propertiesTag = blockTag.getCompound("Properties");
                    if (propertiesTag.contains("mode") && propertiesTag.getString("mode").equals("corner")) {
                        cornerStructureBlockIndex = i;
                        break;
                    }
                }
            }
        }
        if (cornerStructureBlockIndex == -1) {
            Tongdarailway.LOGGER.warn("The road position cannot be determined, and no corner structure block has been found.");
        }

        // 解析方块数据并构建体素网格
        int[][][] voxelGrid = parseBlocks(rootTag.getList("blocks", Tag.TAG_COMPOUND), size, palette, cornerStructureBlockIndex);

        return new VoxelGrid(palette, voxelGrid, size);
    }

    /**
     * 解析调色板
     */
    private List<BlockState> parsePalette(ListTag paletteTag) {
        List<BlockState> palette = new ArrayList<>();

        // 索引 0 总是代表空位
        palette.add(Blocks.STRUCTURE_VOID.defaultBlockState());

        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag blockTag = paletteTag.getCompound(i);
            BlockState blockState = parseBlockState(blockTag);
            palette.add(blockState);
        }

        return palette;
    }

    /**
     * 解析单个方块状态
     */
    private BlockState parseBlockState(CompoundTag blockTag) {
        try {
            String blockName = blockTag.getString("Name");
            ResourceLocation blockId = ResourceLocation.parse(blockName);
            Block block = BuiltInRegistries.BLOCK.get(blockId);

            BlockState blockState = block.defaultBlockState();

            // 解析方块属性（如果有）
            if (blockTag.contains("Properties", Tag.TAG_COMPOUND)) {
                CompoundTag propertiesTag = blockTag.getCompound("Properties");
                blockState = applyProperties(blockState, propertiesTag);
            }

            return blockState;
        } catch (Exception e) {
            Tongdarailway.LOGGER.error(e.getMessage());
            return Blocks.STONE.defaultBlockState();
        }
    }

    /**
     * 应用方块属性
     */
    private BlockState applyProperties(BlockState blockState, CompoundTag propertiesTag) {
        BlockState resultState = blockState;

        for (String propertyName : propertiesTag.getAllKeys()) {
            String propertyValue = propertiesTag.getString(propertyName);

            // 查找对应的方块属性
            Optional<Property<?>> property = findProperty(blockState, propertyName);

            if (property.isPresent()) {
                resultState = setPropertyValue(resultState, property.get(), propertyValue);
            }
        }

        return resultState;
    }

    /**
     * 查找方块属性
     */
    private Optional<Property<?>> findProperty(BlockState blockState, String propertyName) {
        return blockState.getProperties().stream()
                .filter(prop -> prop.getName().equals(propertyName))
                .findFirst();
    }

    /**
     * 设置方块属性值
     */
    private <T extends Comparable<T>> BlockState setPropertyValue(
            BlockState blockState, Property<T> property, String value) {

        Optional<T> propertyValue = property.getValue(value);
        if (propertyValue.isPresent()) {
            return blockState.setValue(property, propertyValue.get());
        } else {
            throw new IllegalArgumentException(
                    "无效的属性值: " + value + " 对于属性: " + property.getName());
        }
    }

    /**
     * 解析方块数据并构建体素网格
     */
    private int[][][] parseBlocks(ListTag blocksTag, BlockPos size, List<BlockState> palette, int cornerStructureBlockIndex) {
        int[][][] voxelGrid = new int[size.getX()][size.getY()][size.getZ()];

        // 初始化网格为 -1（空气）
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    voxelGrid[x][y][z] = 0;
                }
            }
        }

        // 填充实际方块
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag blockTag = blocksTag.getCompound(i);

            // 读取位置
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            int x = posTag.getInt(0);
            int y = posTag.getInt(1);
            int z = posTag.getInt(2);

            // 读取调色板索引
            int state = blockTag.getInt("state");

            // 确保位置在范围内
            if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
                // 读取角落结构方块高度 标记路面位置
                if (state == cornerStructureBlockIndex) {
                    roadbedHeight = y-1;
                    int airState;
                    if (palette.contains(Blocks.AIR.defaultBlockState())) {
                        airState = palette.indexOf(Blocks.AIR.defaultBlockState());
                    } else {
                        palette.add(Blocks.AIR.defaultBlockState());
                        airState = palette.size() - 1;
                    }
                    voxelGrid[x][y][z] = airState;
                } else {
                    voxelGrid[x][y][z] = state + 1;
                }
            }
        }

        return voxelGrid;
    }

    public int getDataVersion() {
        return dataVersion;
    }
}

class VoxelGrid {
    private final List<BlockState> palette;
    private final int[][][] voxelGrid;
    private final BlockPos size;

    public int offsetX = 0;
    public int offsetY = 0;
    public int offsetZ = 0;

    public VoxelGrid(List<BlockState> palette, int[][][] voxelGrid, BlockPos size) {
        this.palette = palette;
        this.voxelGrid = voxelGrid;
        this.size = size;
    }

    public VoxelGrid(List<BlockState> palette, BlockPos size) {
        this.palette = palette;
        this.voxelGrid = new int[size.getX()][size.getY()][size.getZ()];
        this.size = size;
    }

    // Getters
    public List<BlockState> getPalette() { return palette; }
    public int[][][] getVoxelGrid() { return voxelGrid; }
    public BlockPos getSize() { return size; }

    public int getWidth() { return size.getX(); }
    public int getHeight() { return size.getY(); }
    public int getDepth() { return size.getZ(); }

    public ListTag getBlocks() {
        ListTag blocks = new ListTag();
        for (int i = 0; i < size.getX(); i++) {
            for (int j = 0; j < size.getY(); j++) {
                for (int k = 0; k < size.getZ(); k++) {
                    if (voxelGrid[i][j][k] != 0) {
                        CompoundTag tag = new CompoundTag();
                        ListTag pos = new ListTag();
                        pos.add(IntTag.valueOf(i - offsetX));
                        pos.add(IntTag.valueOf(j - offsetY));
                        pos.add(IntTag.valueOf(k - offsetZ));
                        tag.put("pos", pos);
                        tag.putInt("state", voxelGrid[i][j][k] - 1);
                        blocks.add(tag);
                    }
                }
            }
        }

        return blocks;
    }

    public ListTag getPaletteTag() {
        ListTag paletteTag = new ListTag();

        if (!palette.isEmpty()) {
            palette.removeFirst();
            for (BlockState block : palette) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Name", BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
                paletteTag.add(tag);
            }
        }

        return paletteTag;
    }

    public BlockState getBlockState(int x, int y, int z) {
        if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
            int paletteIndex = voxelGrid[x][y][z];
            if (paletteIndex > 0 && paletteIndex < palette.size()) {
                var blockState = palette.get(paletteIndex);
                if (blockState.is(Blocks.STRUCTURE_VOID))
                    return null; // 结构空位
                return blockState;
            }
        }
        return null; // 空气或无方块
    }

    public void setVoxel(int x, int y, int z, int value) {
        if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
            voxelGrid[x][y][z] = value;
        }
    }

    public int getVoxel(int x, int y, int z) {
        if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
            return voxelGrid[x][y][z];
        }
        return -1; // 默认空体素
    }
}
