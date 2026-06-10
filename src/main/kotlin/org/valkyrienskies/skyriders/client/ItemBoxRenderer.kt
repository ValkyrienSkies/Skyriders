package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.ItemBoxEntity

class ItemBoxRenderer(context: EntityRendererProvider.Context) : EntityRenderer<ItemBoxEntity>(context) {
    override fun render(
        entity: ItemBoxEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val recharging = entity.recharging
        val alpha = if (recharging) 0.34f else 0.92f
        val boxBuffer = bufferSource.getBuffer(RenderType.entityTranslucent(BOX_TEXTURE))
        // Base rendering is intentionally disabled for now. Entity renderers are
        // happier with one texture path, and mixing block/atlas textures here
        // caused atlas and BufferBuilder issues.

        poseStack.pushPose()
        poseStack.translate(0.0, 1.08, 0.0)
        val time = if (recharging) entity.frozenRotationTick.toFloat() else entity.tickCount.toFloat() + partialTick
        poseStack.mulPose(Axis.YP.rotationDegrees(time * 5.2f))
        poseStack.mulPose(Axis.XP.rotationDegrees(time * 3.1f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(time * 2.4f))
        addBox(
            poseStack = poseStack,
            buffer = boxBuffer,
            minX = -0.43f,
            minY = -0.43f,
            minZ = -0.43f,
            maxX = 0.43f,
            maxY = 0.43f,
            maxZ = 0.43f,
            red = 1.0f,
            green = 1.0f,
            blue = 1.0f,
            alpha = alpha,
            packedLight = packedLight,
            packedOverlay = if (recharging) OverlayTexture.pack(1.0f, true) else OverlayTexture.NO_OVERLAY
        )
        poseStack.popPose()

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    override fun getTextureLocation(entity: ItemBoxEntity): ResourceLocation = TEXTURE

    private fun addBox(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        packedLight: Int,
        packedOverlay: Int = OverlayTexture.NO_OVERLAY,
        uMin: Float = 0.0f,
        uMax: Float = 1.0f,
        vMin: Float = 0.0f,
        vMax: Float = 1.0f
    ) {
        val pose = poseStack.last()
        val matrix = pose.pose()
        val normal = pose.normal()
        face(buffer, matrix, normal, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, 0.0f, 0.0f, 1.0f, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        face(buffer, matrix, normal, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, 0.0f, 0.0f, -1.0f, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        face(buffer, matrix, normal, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, -1.0f, 0.0f, 0.0f, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        face(buffer, matrix, normal, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, 1.0f, 0.0f, 0.0f, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        face(buffer, matrix, normal, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ, 0.0f, 1.0f, 0.0f, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        face(buffer, matrix, normal, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, 0.0f, -1.0f, 0.0f, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
    }

    private fun face(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        x3: Float,
        y3: Float,
        z3: Float,
        x4: Float,
        y4: Float,
        z4: Float,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        packedLight: Int,
        packedOverlay: Int = OverlayTexture.NO_OVERLAY,
        uMin: Float = 0.0f,
        uMax: Float = 1.0f,
        vMin: Float = 0.0f,
        vMax: Float = 1.0f
    ) {
        vertex(buffer, matrix, normal, x1, y1, z1, 0.0f, 1.0f, normalX, normalY, normalZ, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        vertex(buffer, matrix, normal, x2, y2, z2, 1.0f, 1.0f, normalX, normalY, normalZ, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        vertex(buffer, matrix, normal, x3, y3, z3, 1.0f, 0.0f, normalX, normalY, normalZ, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
        vertex(buffer, matrix, normal, x4, y4, z4, 0.0f, 0.0f, normalX, normalY, normalZ, red, green, blue, alpha, packedLight, packedOverlay, uMin, uMax, vMin, vMax)
    }

    private fun vertex(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        packedLight: Int,
        packedOverlay: Int = OverlayTexture.NO_OVERLAY,
        uMin: Float = 0.0f,
        uMax: Float = 1.0f,
        vMin: Float = 0.0f,
        vMax: Float = 1.0f
    ) {
        val finalU = uMin + (uMax - uMin) * u
        val finalV = vMin + (vMax - vMin) * v
        buffer.vertex(matrix, x, y, z)
            .color(red, green, blue, alpha)
            .uv(finalU, finalV)
            .overlayCoords(packedOverlay)
            .uv2(packedLight)
            .normal(normal, normalX, normalY, normalZ)
            .endVertex()
    }

    companion object {
        private val BOX_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/item_box.png")
        private val TEXTURE = BOX_TEXTURE
    }
}
