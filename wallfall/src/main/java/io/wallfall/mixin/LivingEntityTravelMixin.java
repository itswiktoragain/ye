package io.wallfall.mixin;

import io.wallfall.WallfallClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void wallfall$useDirectionalTravel(Vec3d movementInput, CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity player &&
                WallfallClient.getDirection() != Direction.DOWN) {
            WallfallClient.simulateTravel(player);
            ci.cancel();
        }
    }
}
