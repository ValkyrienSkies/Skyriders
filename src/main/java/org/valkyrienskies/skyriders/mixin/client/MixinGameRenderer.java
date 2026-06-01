package org.valkyrienskies.skyriders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.skyriders.client.BikeClientMountTransforms;
import org.valkyrienskies.skyriders.mixinduck.BikeCameraDuck;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Camera mainCamera;

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void skyriders$addBikeSpeedFov(
        final Camera camera,
        final float partialTick,
        final boolean useFovSetting,
        final CallbackInfoReturnable<Double> cir
    ) {
        final double speed = BikeClientMountTransforms.getMountedBikeSpeed(this.minecraft.getCameraEntity());
        if (speed <= 0.0 || !Double.isFinite(speed)) {
            return;
        }

        final double topSpeed = BikeClientMountTransforms.getMountedBikeWheelTopSpeed(this.minecraft.getCameraEntity());
        final double startSpeed = topSpeed * 0.2;
        final double fullEffectSpeed = topSpeed * 1.15;
        final double t = skyriders$smoothstep(startSpeed, fullEffectSpeed, speed);
        cir.setReturnValue(cir.getReturnValueD() + t * 8.0);
    }

    private double skyriders$smoothstep(final double edge0, final double edge1, final double value) {
        final double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    @WrapOperation(
        method = "pick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"
        )
    )
    private HitResult skyriders$pickWithBikeCamera(
        final Entity entity,
        final double maxDistance,
        final float partialTick,
        final boolean includeFluids,
        final Operation<HitResult> original
    ) {
        if (!((BikeCameraDuck) this.mainCamera).skyriders$updateBikeLook(entity, partialTick)) {
            return original.call(entity, maxDistance, partialTick, includeFluids);
        }

        final Vec3 origin = skyriders$getBikeCameraPickPosition(entity);
        final Vec3 look = ((BikeCameraDuck) this.mainCamera).skyriders$getBikeLookVector();
        final Vec3 target = origin.add(look.x * maxDistance, look.y * maxDistance, look.z * maxDistance);
        return entity.level().clip(new ClipContext(
            origin,
            target,
            ClipContext.Block.OUTLINE,
            includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE,
            entity
        ));
    }

    @WrapOperation(
        method = "pick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 skyriders$getBikeCameraPickOrigin(
        final Entity entity,
        final float partialTick,
        final Operation<Vec3> original
    ) {
        if (BikeClientMountTransforms.getMountedBikeRenderTransform(entity) == null) {
            return original.call(entity, partialTick);
        }
        return skyriders$getBikeCameraPickPosition(entity);
    }

    @WrapOperation(
        method = "pick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 skyriders$getBikeCameraPickDirection(
        final Entity entity,
        final float partialTick,
        final Operation<Vec3> original
    ) {
        if (!((BikeCameraDuck) this.mainCamera).skyriders$updateBikeLook(entity, partialTick)) {
            return original.call(entity, partialTick);
        }
        return ((BikeCameraDuck) this.mainCamera).skyriders$getBikeLookVector();
    }

    private Vec3 skyriders$getBikeCameraPickPosition(final Entity entity) {
        final Vec3 mountedCameraPosition =
            BikeClientMountTransforms.getMountedBikeCameraPosition(entity, entity.getEyeHeight());
        return mountedCameraPosition != null ? mountedCameraPosition : entity.getEyePosition();
    }

    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",
            ordinal = 0
        )
    )
    private void skyriders$applyBikeCameraRoll(
        final PoseStack poseStack,
        final org.joml.Quaternionf quaternion,
        final Operation<Void> original
    ) {
        if (BikeClientMountTransforms.getMountedBikeRenderTransform(this.minecraft.getCameraEntity()) != null) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(((BikeCameraDuck) this.mainCamera).skyriders$getZRot()));
        }
        original.call(poseStack, quaternion);
    }
}
