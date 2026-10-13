package io.wallfall.mixin;

import io.wallfall.WallfallState;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerRendererMixin {
    private static final Map<PlayerEntityRenderState, Direction> WALLFALL_ORIENTATION = new WeakHashMap<>();

    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void wallfall$rememberGravity(AbstractClientPlayerEntity player,
            PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        WALLFALL_ORIENTATION.put(state, WallfallState.get(player));
    }

    @Inject(method = "setupTransforms", at = @At("HEAD"))
    private void wallfall$rotateModel(PlayerEntityRenderState state, MatrixStack matrices,
            float bodyYaw, float baseHeight, CallbackInfo ci) {
        Direction dir = WALLFALL_ORIENTATION.getOrDefault(state, Direction.DOWN);
        if (dir == Direction.DOWN) return;
        Quaternionf rot = new Quaternionf().rotationTo(
                new Vector3f(0, -1, 0),
                new Vector3f(dir.getOffsetX(), dir.getOffsetY(), dir.getOffsetZ()));
        if (Float.isFinite(rot.x) && Float.isFinite(rot.y) && Float.isFinite(rot.z) && Float.isFinite(rot.w))
            matrices.multiply(rot.normalize());
    }
}
