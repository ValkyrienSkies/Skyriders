package org.valkyrienskies.skyriders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
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
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",
            ordinal = 0
        ),
        slice = @Slice(
            from = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"
            ),
            to = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"
            )
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
