package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.entity.ProjectileImpactEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

object VehicleDamageEvents {
    private const val MELEE_DAMAGE_SCALE = 1.35
    private const val PROJECTILE_BASE_DAMAGE = 7.0
    private const val PROJECTILE_SPEED_DAMAGE_SCALE = 4.0
    private const val VANILLA_EXPLOSION_DAMAGE = 36.0
    private const val VANILLA_EXPLOSION_RADIUS = 4.25

    @SubscribeEvent
    fun onExplosion(event: ExplosionEvent.Detonate) {
        val level = event.level as? ServerLevel ?: return
        val pos = event.explosion.position
        VehicleDamage.damageExplosion(
            level = level,
            origin = Vec3(pos.x, pos.y, pos.z),
            baseDamage = VANILLA_EXPLOSION_DAMAGE,
            radius = VANILLA_EXPLOSION_RADIUS
        )
    }

    @SubscribeEvent
    fun onProjectileImpact(event: ProjectileImpactEvent) {
        val projectile = event.projectile
        val level = projectile.level() as? ServerLevel ?: return
        val hit = event.rayTraceResult
        val damage = PROJECTILE_BASE_DAMAGE + projectile.deltaMovement.length() * PROJECTILE_SPEED_DAMAGE_SCALE

        val seat = (hit as? EntityHitResult)?.entity as? BikeSeatEntity
        if (seat != null) {
            val vehicle = VehicleManager.getVehicle(level.dimensionId, seat.bodyId) ?: return
            VehicleDamage.damageAt(level, vehicle, hit.location, damage)
            return
        }

        VehicleInteractionHandler.findVehicleOnRay(level, projectile.position(), hit.location)?.let { target ->
            VehicleDamage.damageAt(level, target.vehicle, hit.location, damage)
            event.impactResult = ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT
        }
    }

    @SubscribeEvent
    fun onAttackEntity(event: AttackEntityEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val level = player.level() as? ServerLevel ?: return
        val seat = event.target as? BikeSeatEntity ?: return
        val vehicle = VehicleManager.getVehicle(level.dimensionId, seat.bodyId) ?: return
        VehicleDamage.damageAt(level, vehicle, player.eyePosition.add(player.lookAngle.scale(2.0)), meleeDamage(player))
    }

    @SubscribeEvent
    fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        if (event.action != PlayerInteractEvent.LeftClickBlock.Action.START) return
        val player = event.entity as? ServerPlayer ?: return
        val level = player.level() as? ServerLevel ?: return
        val start = player.eyePosition
        val end = event.pos.center
        val target = VehicleInteractionHandler.findVehicleOnRay(level, start, end) ?: return
        if (target.distanceSqr > start.distanceToSqr(end)) return

        VehicleDamage.damageAt(level, target.vehicle, start.add(player.lookAngle.scale(sqrtDistance(target.distanceSqr))), meleeDamage(player))
        event.isCanceled = true
    }

    private fun meleeDamage(player: ServerPlayer): Double {
        val base = player.getAttributeValue(Attributes.ATTACK_DAMAGE).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return base * MELEE_DAMAGE_SCALE
    }

    private fun sqrtDistance(distanceSqr: Double): Double {
        return kotlin.math.sqrt(distanceSqr.coerceAtLeast(0.0))
    }
}
