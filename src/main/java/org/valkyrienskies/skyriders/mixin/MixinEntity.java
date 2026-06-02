package org.valkyrienskies.skyriders.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity;

@Mixin(Entity.class)
public abstract class MixinEntity {
    @Inject(method = "isShiftKeyDown()Z", at = @At("HEAD"), cancellable = true)
    private void skyriders$suppressBikeSneakState(final CallbackInfoReturnable<Boolean> cir) {
        final Entity self = (Entity) (Object) this;
        if (self.getVehicle() instanceof BikeSeatEntity) {
            cir.setReturnValue(false);
        }
    }
}
