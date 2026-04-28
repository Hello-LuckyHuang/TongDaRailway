package com.hxzhitang.tongdarailway.structure;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

public abstract class ModTemplate {
    protected VoxelGrid voxelGrid;

    protected int heightOffset = 0;

    public final Rotation rotation;

    public ModTemplate(Path path, int heightOffset) {
        this(path, heightOffset, Rotation.NONE);
    }

    public ModTemplate(CompoundTag nbt, int heightOffset) {
        this(nbt, heightOffset, Rotation.NONE);
    }

    public ModTemplate(Path path, int heightOffset, Rotation rotation) {
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(path, StandardOpenOption.READ))))) {
            CompoundTag rootTag = NbtIo.read(stream, NbtAccounter.create(0x20000000L));
            voxelGrid = parseStructureNBT(rootTag, rotation);
        } catch (Exception e) {
            Tongdarailway.LOGGER.error(e.getMessage());
        }

        this.heightOffset = heightOffset;
        this.rotation = rotation;
    }

    public ModTemplate(CompoundTag nbt, int heightOffset, Rotation rotation) {
        voxelGrid = parseStructureNBT(nbt, rotation);

        this.heightOffset = heightOffset;
        this.rotation = rotation;
    }

    public int getWidth() {return voxelGrid.getWidth();}
    public int getHeight() {return voxelGrid.getHeight();}
    public int getDepth() {return voxelGrid.getDepth();}

    public int getUpperBound() {
        return voxelGrid.getHeight() - heightOffset + 1;
    }

    public int getLowerBound() {
        return -(heightOffset + 1);
    }

    public abstract boolean isInVoxel(double x, double y, double z);

    public boolean isInVoxel(Vec3 pos) {
        return isInVoxel(pos.x(), pos.y(), pos.z());
    }

    public abstract BlockState getBlockState(double x, double y, double z);

    public BlockState getBlockState(Vec3 pos) {
        return getBlockState(pos.x(), pos.y(), pos.z());
    }

    /**
     * 解析结构 NBT 数据
     */
    private VoxelGrid parseStructureNBT(CompoundTag rootTag, Rotation rotation) {
        if (rootTag == null) return null;

        // 读取基本信息
        ListTag sizeTag = rootTag.getList("size", Tag.TAG_INT);
        int x = sizeTag.getInt(0), y = sizeTag.getInt(1), z = sizeTag.getInt(2);
        BlockPos size = new BlockPos(x, y, z);
        StructureTransform transform = createRotation(size, rotation);
        size = getTransformedSize(size, rotation);

        // 解析调色板
        List<BlockState> palette = parsePalette(rootTag.getList("palette", Tag.TAG_COMPOUND));
        palette = palette.stream().map(transform::apply).toList();

        // 解析方块数据并构建体素网格
        int[][][] voxelGrid = parseBlocks(rootTag.getList("blocks", Tag.TAG_COMPOUND), size, transform);

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
            return Blocks.AIR.defaultBlockState();
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
    private int[][][] parseBlocks(ListTag blocksTag, BlockPos size, StructureTransform transform) {
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

            BlockPos originalPos = new BlockPos(x, y, z);
            BlockPos tPos = transform.apply(originalPos);

            x = tPos.getX();
            y = tPos.getY();
            z = tPos.getZ();

            // 读取调色板索引
            int state = blockTag.getInt("state");

            // 确保位置在范围内
            if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
                voxelGrid[x][y][z] = state + 1;
            }
        }

        return voxelGrid;
    }

    private static StructureTransform createRotation(BlockPos size, Rotation rotation) {
        // 创建原始Y轴旋转变换
        StructureTransform originalTransform = new StructureTransform(
                BlockPos.ZERO,
                Direction.Axis.Y,
                rotation,
                Mirror.NONE
        );

        // 计算原始结构的所有角点
        List<BlockPos> corners = Arrays.asList(
                BlockPos.ZERO,
                new BlockPos(size.getX() - 1, 0, 0),
                new BlockPos(0, size.getY() - 1, 0),
                new BlockPos(0, 0, size.getZ() - 1),
                new BlockPos(size.getX() - 1, size.getY() - 1, 0),
                new BlockPos(size.getX() - 1, 0, size.getZ() - 1),
                new BlockPos(0, size.getY() - 1, size.getZ() - 1),
                new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1)
        );

        // 应用旋转变换到所有角点
        List<BlockPos> transformedCorners = new ArrayList<>();
        for (BlockPos corner : corners) {
            transformedCorners.add(originalTransform.apply(corner));
        }

        // 找到变换后的最小坐标
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (BlockPos pos : transformedCorners) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
        }

        // 计算需要的偏移量
        BlockPos offset = new BlockPos(-minX, -minY, -minZ);

        // 创建包含偏移的最终变换
        return new StructureTransform(
                offset,
                Direction.Axis.Y,
                rotation,
                Mirror.NONE
        );
    }

    private static BlockPos getTransformedSize(BlockPos originalSize, Rotation rotation) {
        if (rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180) {
            return originalSize;
        }

        // 对于Y轴旋转，X和Z维度会交换
        int newX = originalSize.getZ();
        int newY = originalSize.getY();
        int newZ = originalSize.getX();

        return new BlockPos(newX, newY, newZ);
    }
}
