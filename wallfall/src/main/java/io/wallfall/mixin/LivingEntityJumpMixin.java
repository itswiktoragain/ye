package io.wallfall.mixin;

import io.wallfall.WallfallClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpMixin {
    @Inject(method = "jump", at = @At("HEAD"), cancellable = true)
    private void wallfall$overrideVerticalJump(CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity &&
                WallfallClient.getDirection() != Direction.DOWN) {
            ci.cancel();
        }
    }
}
