package com.hxzhitang.tongdarailway.structure;

import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

public record TrackPutInfo(
        BlockPos pos,
        TrackShape shape,
        BezierInfo bezier,
        TrackShape endShape
) {
    public static TrackPutInfo getByDir(BlockPos pos, Vec3 dir, BezierInfo bezier) {
        if (bezier != null)
            return new TrackPutInfo(pos, getShape(dir), bezier, getShape(bezier.endAxis));
        return new TrackPutInfo(pos, getShape(dir), null, null);
    }

    private static TrackShape getShape(Vec3 dir) {
        TrackShape shape = TrackShape.XO;
        if (Math.abs(dir.x) < 1e-6) {
            // 检查是否指向z轴正负方向（x接近0）
            shape = TrackShape.ZO;
        } else if (Math.abs(dir.z) < 1e-6) {
            // 检查是否指向x轴正负方向（z接近0）
            shape = TrackShape.XO;
        } else if (dir.x * dir.z > 0) {
            // 检查是否指向第一第三象限（x和z同号）
            shape = TrackShape.PD;
        } else if (dir.x * dir.z < 0) {
            // 检查是否指向第二第四象限（x和z异号）
            shape = TrackShape.ND;
        }

        return shape;
    }

    public CompoundTag toNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("x", pos.getX());
        nbt.putInt("y", pos.getY());
        nbt.putInt("z", pos.getZ());
        nbt.putString("shape", shape.name());
        if (bezier != null) {
            nbt.put("bezier", bezier.toNbt());
            nbt.putString("endShape", endShape.name());
        }

        return nbt;
    }

    public static TrackPutInfo fromNBT(CompoundTag nbt) {
        BlockPos pos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));
        TrackShape shape = TrackShape.valueOf(nbt.getString("shape"));
        BezierInfo bezier = null;
        TrackShape endShape = null;
        boolean hasBezier = nbt.contains("bezier");
        if (hasBezier) {
            bezier = BezierInfo.fromNbt(nbt.getCompound("bezier"));
            endShape = TrackShape.valueOf(nbt.getString("endShape"));
        }

        return new TrackPutInfo(pos, shape, bezier, endShape);
    }

    public record BezierInfo(
            Vec3 start,
            Vec3 startAxis,
            Vec3 endOffset,
            Vec3 endAxis
    ) {
        public CompoundTag toNbt() {
            CompoundTag nbt = new CompoundTag();
            nbt.putDouble("startX", start.x);
            nbt.putDouble("startY", start.y);
            nbt.putDouble("startZ", start.z);
            nbt.putDouble("startAxisX", startAxis.x);
            nbt.putDouble("startAxisY", startAxis.y);
            nbt.putDouble("startAxisZ", startAxis.z);
            nbt.putDouble("endOffsetX", endOffset.x);
            nbt.putDouble("endOffsetY", endOffset.y);
            nbt.putDouble("endOffsetZ", endOffset.z);
            nbt.putDouble("endAxisX", endAxis.x);
            nbt.putDouble("endAxisY", endAxis.y);
            nbt.putDouble("endAxisZ", endAxis.z);
            return nbt;
        }

        public static BezierInfo fromNbt(CompoundTag nbt) {
            return new BezierInfo(
                    new Vec3(nbt.getDouble("startX"), nbt.getDouble("startY"), nbt.getDouble("startZ")),
                    new Vec3(nbt.getDouble("startAxisX"), nbt.getDouble("startAxisY"), nbt.getDouble("startAxisZ")),
                    new Vec3(nbt.getDouble("endOffsetX"), nbt.getDouble("endOffsetY"), nbt.getDouble("endOffsetZ")),
                    new Vec3(nbt.getDouble("endAxisX"), nbt.getDouble("endAxisY"), nbt.getDouble("endAxisZ"))
            );
        }
    }
}
