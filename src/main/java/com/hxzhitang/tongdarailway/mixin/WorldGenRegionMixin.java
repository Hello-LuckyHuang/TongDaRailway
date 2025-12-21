package com.hxzhitang.tongdarailway.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldGenRegion.class)
public class WorldGenRegionMixin {
    @WrapOperation(
            method = "setBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/WorldGenRegion;ensureCanWrite(Lnet/minecraft/core/BlockPos;)Z")
    )
    private boolean bypassExpensiveCalculationIfNecessary(WorldGenRegion instance, BlockPos blockPos, Operation<Boolean> original, BlockPos pos, BlockState state) {
        if (state.is(AllBlocks.TRACK.get())) {
            return true;
        } else {
            return original.call(instance, blockPos);
        }
    }
}
