package com.hxzhitang.tongdarailway.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    /**
     * 修复 Create 轨道在没有 BE 时触发的日志警告
     * 原理：拦截 hasBlockEntity 的判断，如果是轨道且 HAS_BE 为 false，则强制返回 false
     */
    @WrapOperation(
            method = "setBlockState", // 1.20.1 建议使用简写，Mixin 会自动匹配混淆名
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;hasBlockEntity()Z")
    )
    private boolean tongdarailway$patchTrackHasBE(BlockState instance, Operation<Boolean> original, BlockPos pos, BlockState state, boolean isMoving) {
        // 注意：1.20.1 映射中 setBlockState 的本地变量顺序可能不同
        // 建议通过 instance 检查当前上下文，或直接检查 state 参数
        if (state.is(AllBlocks.TRACK.get()) && !state.getValue(TrackBlock.HAS_BE)) {
            return false;
        }
        return original.call(instance);
    }

    /**
     * 阻止尝试升级不存在的轨道 BlockEntity
     */
    @Inject(
            method = "promotePendingBlockEntity",
            at = @At(value = "HEAD"),
            cancellable = true
    )
    private void tongdarailway$preventTrackBEPromotion(BlockPos pos, CompoundTag tag, CallbackInfoReturnable<BlockEntity> cir) {
        BlockState state = this.getBlockState(pos);
        // 使用 .get() 以确保 1.20.1 RegistryObject 的兼容性
        if (state.is(AllBlocks.TRACK.get()) && state.hasProperty(TrackBlock.HAS_BE) && !state.getValue(TrackBlock.HAS_BE)) {
            cir.setReturnValue(null);
        }
    }
}