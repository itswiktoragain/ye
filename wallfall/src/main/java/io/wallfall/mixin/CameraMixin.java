package io.wallfall.mixin;

import io.wallfall.WallfallClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void wallfall$orientCamera(BlockView area, Entity focusedEntity,
                                       boolean thirdPerson, boolean inverseView, float tickDelta,
                                       CallbackInfo ci) {
        if (focusedEntity != MinecraftClient.getInstance().player) return;
        Direction wall = WallfallClient.getDirection();
        if (wall == Direction.DOWN) return;

        Camera self = (Camera) (Object) this;
        Quaternionf tilt = new Quaternionf().rotationTo(
                new Vector3f(0, -1, 0),
                new Vector3f(wall.getOffsetX(), wall.getOffsetY(), wall.getOffsetZ())
        );
        if (Float.isFinite(tilt.x) && Float.isFinite(tilt.y) && Float.isFinite(tilt.z) && Float.isFinite(tilt.w))
            self.getRotation().premul(tilt.normalize());
    }
}
