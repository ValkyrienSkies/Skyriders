package org.valkyrienskies.skyriders.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity;

@Mixin(Player.class)
public abstract class MixinLivingEntity {
    //im stupid this is player ignore the mixin name
    @Inject(method = "wantsToStopRiding()Z", at = @At("HEAD"), cancellable = true)
    private void skyriders$keepShiftFromDismountingBike(final CallbackInfoReturnable<Boolean> cir) {
        final Entity self = (Entity) (Object) this;
        if (self.getVehicle() instanceof BikeSeatEntity) {
            cir.setReturnValue(false);
        }
    }
}
