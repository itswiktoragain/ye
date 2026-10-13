package io.wallfall.mixin;

import io.wallfall.WallfallState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityGravityBoxMixin {
    @Inject(method = "calculateBoundingBox", at = @At("RETURN"), cancellable = true)
    private void wallfall$rotatedHitbox(CallbackInfoReturnable<Box> cir) {
        Entity self = (Entity)(Object)this;
        if (!(self instanceof PlayerEntity)) return;
        Direction gravity = WallfallState.get(self);
        if (gravity.getAxis().isHorizontal())
            cir.setReturnValue(WallfallState.rotatedBox(self, gravity));
    }
}
