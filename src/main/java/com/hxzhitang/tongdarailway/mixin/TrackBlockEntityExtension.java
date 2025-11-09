package com.hxzhitang.tongdarailway.mixin;

import com.hxzhitang.tongdarailway.blocks.ITrackPreGenExtension;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockEntityTilt;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(TrackBlockEntity.class)
public abstract class TrackBlockEntityExtension extends SmartBlockEntity implements ITrackPreGenExtension {

    @Shadow
    public abstract void addConnection(BezierConnection connection);

    @Shadow
    public TrackBlockEntityTilt tilt;

    public TrackBlockEntityExtension(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Unique
    List<BezierConnection> tongDaRailway$preConnections = new ArrayList<>();

    @Unique
    @Override
    public void addConnectionToPreGen(BezierConnection connection) {
        tongDaRailway$preConnections.add(connection);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void tick$addConnection(CallbackInfo ci) {
        if(!tongDaRailway$preConnections.isEmpty() && !level.isClientSide()){
            tongDaRailway$preConnections.forEach(this::addConnection);
            tilt.tryApplySmoothing();
            tongDaRailway$preConnections.clear();
        }
    }
}
