package org.valkyrienskies.skyriders.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.skyriders.client.BikeClientMountTransforms;
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity;

@Mixin(value = EntityRenderDispatcher.class, priority = 500)
public abstract class MixinEntityRenderDispatcher {
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            shift = At.Shift.BEFORE
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private <T extends Entity> void skyriders$renderBikeMountedEntityAtRenderTransform(
        final T entity,
        final double x,
        final double y,
        final double z,
        final float rotationYaw,
        final float partialTicks,
        final PoseStack poseStack,
        final MultiBufferSource buffer,
        final int packedLight,
        final CallbackInfo ci,
        final EntityRenderer<T> entityRenderer
    ) {
        final BikeSeatEntity seat = skyriders$getBikeSeat(entity);
        if (seat == null) {
            return;
        }

        final BodyTransform transform = BikeClientMountTransforms.getBikeRenderTransform(seat);
        if (transform == null) {
            return;
        }

        final Vec3 vanillaPosition = entity.getPosition(partialTicks);
        final Vec3 renderOffset = entityRenderer.getRenderOffset(entity, partialTicks);
        final Vector3d bikePosition = BikeClientMountTransforms.getBikeMountedEntityRenderPosition(seat, entity);
        if (bikePosition == null) {
            return;
        }

        poseStack.popPose();
        poseStack.pushPose();
        poseStack.translate(
            bikePosition.x + x - vanillaPosition.x,
            bikePosition.y + y - vanillaPosition.y,
            bikePosition.z + z - vanillaPosition.z
        );
        poseStack.translate(renderOffset.x, renderOffset.y, renderOffset.z);
    }

    private BikeSeatEntity skyriders$getBikeSeat(final Entity entity) {
        if (entity instanceof BikeSeatEntity bikeSeat) {
            return bikeSeat;
        }
        return entity.getVehicle() instanceof BikeSeatEntity bikeSeat ? bikeSeat : null;
    }
}
