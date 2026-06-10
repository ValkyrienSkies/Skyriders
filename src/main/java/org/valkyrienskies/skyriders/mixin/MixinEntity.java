package org.valkyrienskies.skyriders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.skyriders.content.VehicleEntityCollision;
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity;

@Mixin(Entity.class)
public abstract class MixinEntity {
    @Shadow
    public Level level;

    @Shadow
    public abstract AABB getBoundingBox();

    @Inject(method = "isShiftKeyDown()Z", at = @At("HEAD"), cancellable = true)
    private void skyriders$suppressBikeSneakState(final CallbackInfoReturnable<Boolean> cir) {
        final Entity self = (Entity) (Object) this;
        if (self.getVehicle() instanceof BikeSeatEntity) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(
        method = "move",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 skyriders$collideWithVehicleBodies(final Entity entity, Vec3 movement, final Operation<Vec3> collide) {
        final AABB box = this.getBoundingBox();
        final int subdivisions = (int) (movement.length() / 0.5) + 1;
        final Vec3 substep = movement.scale(1.0 / subdivisions);

        for (int i = 0; i < subdivisions; i++) {
            final Vec3 previousMovement = substep.scale(i);
            final Vec3 partialResult = VehicleEntityCollision.adjustEntityMovementForVehicleBodyCollisions(
                entity,
                substep,
                box.move(previousMovement),
                this.level
            );
            if (partialResult.distanceToSqr(substep) > 1.0e-12) {
                movement = previousMovement.add(partialResult);
                break;
            }
        }

        return collide.call(entity, movement);
    }
}
