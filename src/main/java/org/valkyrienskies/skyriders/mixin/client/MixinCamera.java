package org.valkyrienskies.skyriders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.skyriders.client.BikeClientMountTransforms;

@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow private Entity entity;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;

    @WrapOperation(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setRotation(FF)V"
        )
    )
    private void skyriders$setRotationWithBike(
        final Camera camera,
        final float yaw,
        final float pitch,
        final Operation<Void> original
    ) {
        final BodyTransform transform = BikeClientMountTransforms.getMountedBikeRenderTransform(this.entity);
        if (transform == null) {
            original.call(camera, yaw, pitch);
            return;
        }

        final Quaterniondc vanillaRotation =
            new Quaterniond().rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch)).normalize();
        final Quaterniondc bikeRotation = transform.getRotation().mul(vanillaRotation, new Quaterniond());

        this.xRot = pitch;
        this.yRot = yaw;
        this.rotation.set(bikeRotation);
        this.forwards.set(0.0F, 0.0F, 1.0F);
        this.rotation.transform(this.forwards);
        this.up.set(0.0F, 1.0F, 0.0F);
        this.rotation.transform(this.up);
        this.left.set(1.0F, 0.0F, 0.0F);
        this.rotation.transform(this.left);
    }

    @WrapOperation(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"
        )
    )
    private void skyriders$setPositionWithBikeEyeOffset(
        final Camera camera,
        final double x,
        final double y,
        final double z,
        final Operation<Void> original
    ) {
        final BodyTransform transform = BikeClientMountTransforms.getMountedBikeRenderTransform(this.entity);
        if (transform == null) {
            original.call(camera, x, y, z);
            return;
        }

        final double eyeHeight = Mth.lerp(1.0F, this.eyeHeightOld, this.eyeHeight);
        final Vector3d eyeOffset = transform.getRotation().transform(new Vector3d(0.0, eyeHeight, 0.0));
        original.call(camera, x + eyeOffset.x, y - eyeHeight + eyeOffset.y, z + eyeOffset.z);
    }
}
