package org.valkyrienskies.skyriders.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.skyriders.client.BikeClientMountTransforms;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;

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
}
