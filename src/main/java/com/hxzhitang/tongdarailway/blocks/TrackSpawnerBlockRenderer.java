package com.hxzhitang.tongdarailway.blocks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext; // 1.20.1 已经使用此名称
import net.minecraft.world.item.ItemStack;

public class TrackSpawnerBlockRenderer implements BlockEntityRenderer<TrackSpawnerBlockEntity> {
    private final ItemRenderer itemRenderer;

    public TrackSpawnerBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(TrackSpawnerBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // 确保 Level 不为空，防止获取时间时崩溃
        if (blockEntity.getLevel() == null) return;

        ItemStack itemStack = AllBlocks.TRACK.asStack();
        if (itemStack.isEmpty()) return;

        poseStack.pushPose();

        // 1. 移动到方块中心
        poseStack.translate(0.5, 0.5, 0.5);

        // 2. 添加旋转动画
        // 注意：1.20.1 中 getGameTime() 返回 long，建议强制转 float 参与运算
        float time = (blockEntity.getLevel().getGameTime() + partialTick) * 2.0f;
        poseStack.mulPose(Axis.YP.rotationDegrees(time % 360));

        // 3. 缩放物品大小
        poseStack.scale(0.8f, 0.8f, 0.8f);

        // 4. 渲染物品
        // 在 1.20.1 中，renderStatic 的参数签名如下：
        // (ItemStack, ItemDisplayContext, packedLight, packedOverlay, PoseStack, MultiBufferSource, Level, seed)
        itemRenderer.renderStatic(
                itemStack,
                ItemDisplayContext.FIXED,
                packedLight,
                packedOverlay,
                poseStack,
                bufferSource,
                blockEntity.getLevel(),
                (int) blockEntity.getBlockPos().asLong() // 使用方块坐标作为随机种子，防止动画闪烁
        );

        poseStack.popPose();
    }
}