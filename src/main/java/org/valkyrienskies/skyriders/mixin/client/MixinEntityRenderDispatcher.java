package org.valkyrienskies.skyriders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.skyriders.client.BikeClientMountTransforms;
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity;

@Mixin(value = EntityRenderDispatcher.class, priority = 500)
public abstract class MixinEntityRenderDispatcher {
    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
        )
    )
    private <T extends Entity> void skyriders$renderBikeMountedEntityAtRenderTransform(
        final EntityRenderer<T> renderer,
        final T entity,
        final float rotationYaw,
        final float partialTicks,
        final PoseStack poseStack,
        final MultiBufferSource buffer,
        final int packedLight,
        final Operation<Void> original,
        final T dispatchedEntity,
        final double x,
        final double y,
        final double z,
        final float dispatchedRotationYaw,
        final float dispatchedPartialTicks,
        final PoseStack dispatchedPoseStack,
        final MultiBufferSource dispatchedBuffer,
        final int dispatchedPackedLight
    ) {
        final BikeSeatEntity seat = skyriders$getBikeSeat(entity);
        if (seat == null) {
            original.call(renderer, entity, rotationYaw, partialTicks, poseStack, buffer, packedLight);
            return;
        }

        final Vec3 vanillaPosition = entity.getPosition(partialTicks);
        final Vec3 renderOffset = renderer.getRenderOffset(entity, partialTicks);
        final Vector3d bikePosition = BikeClientMountTransforms.getBikeMountedEntityRenderPosition(seat, entity);
        final Float bikeYaw = BikeClientMountTransforms.getBikeMountedEntityRenderYaw(seat, rotationYaw);
        if (bikePosition == null || bikeYaw == null) {
            original.call(renderer, entity, rotationYaw, partialTicks, poseStack, buffer, packedLight);
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
        original.call(renderer, entity, bikeYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private BikeSeatEntity skyriders$getBikeSeat(final Entity entity) {
        if (entity instanceof BikeSeatEntity bikeSeat) {
            return bikeSeat;
        }
        return entity.getVehicle() instanceof BikeSeatEntity bikeSeat ? bikeSeat : null;
    }
}
