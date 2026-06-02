package org.valkyrienskies.skyriders.client

import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

class BikeSeatRenderer(context: EntityRendererProvider.Context) : EntityRenderer<BikeSeatEntity>(context) {
    override fun getTextureLocation(entity: BikeSeatEntity): ResourceLocation {
        return VehicleManager.getVehicle(entity.level(), entity.bodyId)?.vehicleDefinition?.render?.seatTexture ?: TEXTURE
    }

    companion object {
        private val TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/bikes/debug_bike.png")
    }
}
