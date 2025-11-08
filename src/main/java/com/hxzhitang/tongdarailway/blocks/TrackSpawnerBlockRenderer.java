package com.hxzhitang.tongdarailway.blocks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class TrackSpawnerBlockRenderer implements BlockEntityRenderer<TrackSpawnerBlockEntity> {
    private final ItemRenderer itemRenderer;

    public TrackSpawnerBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(TrackSpawnerBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        ItemStack itemStack = AllBlocks.TRACK.asStack();
        if (itemStack.isEmpty()) return;

        poseStack.pushPose();

        // 移动到方块中心
        poseStack.translate(0.5, 0.5, 0.5);

        // 添加旋转动画
        float time = (blockEntity.getLevel().getGameTime() + partialTick) * 2.0f;
        poseStack.mulPose(Axis.YP.rotationDegrees(time % 360));

        // 缩放物品大小
        poseStack.scale(0.8f, 0.8f, 0.8f);

        // 渲染物品
        itemRenderer.renderStatic(itemStack, ItemDisplayContext.FIXED, packedLight, packedOverlay, poseStack, bufferSource, blockEntity.getLevel(), 0);

        poseStack.popPose();
    }
}