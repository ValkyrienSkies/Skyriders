package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.ProjectileImpactEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import java.util.UUID

object VehicleDamageEvents {
    private const val MELEE_DAMAGE_SCALE = 1.35
    private const val MELEE_RANGE = 5.0
    private const val MELEE_DAMAGE_COOLDOWN_TICKS = 4L
    private const val PROJECTILE_BASE_DAMAGE = 7.0
    private const val PROJECTILE_SPEED_DAMAGE_SCALE = 4.0
    private const val PROJECTILE_DAMAGE_COOLDOWN_TICKS = 10L
    private const val VANILLA_EXPLOSION_DAMAGE = 36.0
    private const val VANILLA_EXPLOSION_RADIUS = 4.25
    private val lastMeleeDamageTickByPlayer = HashMap<UUID, Long>()
    private val lastProjectileDamageTickByProjectile = HashMap<UUID, Long>()

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
        val ignoredBodyId = projectileOwnerBodyId(projectile)

        val seat = (hit as? EntityHitResult)?.entity as? BikeSeatEntity
        if (seat != null) {
            if (ignoredBodyId != null && seat.bodyId == ignoredBodyId) return
            val vehicle = VehicleManager.getVehicle(level.dimensionId, seat.bodyId) ?: return
            VehicleDamage.damageAt(level, vehicle, hit.location, damage)
            lastProjectileDamageTickByProjectile[projectile.uuid] = level.gameTime
            return
        }

        findDamageTargetOnRay(level, projectile.position(), hit.location, ignoredBodyId)?.let { target ->
            VehicleDamage.damageAt(level, target.vehicle, hitPoint(projectile.position(), hit.location, target.hit.distanceSqr), damage)
            lastProjectileDamageTickByProjectile[projectile.uuid] = level.gameTime
            event.impactResult = ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT
        }
    }

    @SubscribeEvent
    fun onLevelTick(event: TickEvent.LevelTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val level = event.level as? ServerLevel ?: return
        val now = level.gameTime
        if (now % 100L == 0L) {
            lastProjectileDamageTickByProjectile.entries.removeIf { now - it.value > 200L }
        }

        level.allEntities.forEach { entity ->
            val projectile = entity as? Projectile ?: return@forEach
            if (!projectile.isAlive) return@forEach
            if (lastProjectileDamageTickByProjectile[projectile.uuid]?.let { now - it < PROJECTILE_DAMAGE_COOLDOWN_TICKS } == true) {
                return@forEach
            }
            val start = Vec3(projectile.xo, projectile.yo, projectile.zo)
            val end = projectile.position()
            if (start.distanceToSqr(end) < 1.0e-6) return@forEach
            val target = findDamageTargetOnRay(level, start, end, projectileOwnerBodyId(projectile)) ?: return@forEach
            val damage = PROJECTILE_BASE_DAMAGE + projectile.deltaMovement.length() * PROJECTILE_SPEED_DAMAGE_SCALE
            VehicleDamage.damageAt(level, target.vehicle, hitPoint(start, end, target.hit.distanceSqr), damage)
            lastProjectileDamageTickByProjectile[projectile.uuid] = now
            projectile.discard()
        }
    }

    @SubscribeEvent
    fun onAttackEntity(event: AttackEntityEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val level = player.level() as? ServerLevel ?: return
        val seat = event.target as? BikeSeatEntity ?: return
        val vehicle = VehicleManager.getVehicle(level.dimensionId, seat.bodyId) ?: return
        damageMelee(player, vehicle, player.eyePosition.add(player.lookAngle.scale(2.0)))
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

        damageMelee(player, target.vehicle, start.add(player.lookAngle.scale(sqrtDistance(target.distanceSqr))))
        event.isCanceled = true
    }

    fun handleClientMeleeAttack(player: ServerPlayer) {
        val level = player.level() as? ServerLevel ?: return
        val start = player.eyePosition
        val end = start.add(player.lookAngle.scale(MELEE_RANGE))
        val target = findDamageTargetOnRay(level, start, end) ?: return
        damageMelee(player, target.vehicle, hitPoint(start, end, target.hit.distanceSqr))
    }

    private fun damageMelee(player: ServerPlayer, vehicle: IVehicle, hitPos: Vec3) {
        val now = player.level().gameTime
        if (lastMeleeDamageTickByPlayer[player.uuid]?.let { now - it < MELEE_DAMAGE_COOLDOWN_TICKS } == true) return
        val level = player.level() as? ServerLevel ?: return
        VehicleDamage.damageAt(level, vehicle, hitPos, meleeDamage(player))
        lastMeleeDamageTickByPlayer[player.uuid] = now
    }

    private fun findDamageTargetOnRay(
        level: ServerLevel,
        start: Vec3,
        end: Vec3,
        ignoredBodyId: Long? = null
    ): DamageRayTarget? {
        return VehicleManager.getVehicles(level)
            .asSequence()
            .filter { vehicle -> ignoredBodyId == null || vehicle.bodyId != ignoredBodyId }
            .flatMap { vehicle -> VehicleInteractionPicker.hitVehicleInteractionZones(vehicle, start, end).asSequence() }
            .filter { hit -> isDamageablePart(hit.vehicle, hit.zone.partId) }
            .groupBy { it.vehicle.bodyId }
            .values
            .asSequence()
            .mapNotNull { hits ->
                val best = hits.minWithOrNull(
                    compareByDescending<VehicleRayHit> { damagePartPriority(it.vehicle, it.zone.partId) }
                        .thenBy { it.distanceSqr }
                ) ?: return@mapNotNull null
                DamageRayTarget(
                    vehicle = best.vehicle,
                    hit = best,
                    vehicleDistanceSqr = hits.minOf { it.distanceSqr }
                )
            }
            .minByOrNull { it.vehicleDistanceSqr }
    }

    private fun isDamageablePart(vehicle: IVehicle, partId: String?): Boolean {
        if (partId == null) return false
        val type = vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId }?.type ?: return false
        return type == VehiclePartTypes.BODY || type == VehiclePartTypes.ENGINE || type == VehiclePartTypes.WHEEL
    }

    private fun damagePartPriority(vehicle: IVehicle, partId: String?): Int {
        val type = vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId }?.type ?: return 0
        return when (type) {
            VehiclePartTypes.WHEEL -> 30
            VehiclePartTypes.ENGINE -> 20
            VehiclePartTypes.BODY -> 10
            else -> 0
        }
    }

    private fun hitPoint(start: Vec3, end: Vec3, distanceSqr: Double): Vec3 {
        val direction = end.subtract(start)
        val length = direction.length()
        if (length <= 1.0e-6) return start
        return start.add(direction.scale(sqrtDistance(distanceSqr) / length))
    }

    private fun projectileOwnerBodyId(projectile: Projectile): Long? {
        return ownerBodyId(projectile.owner)
    }

    private fun ownerBodyId(owner: Entity?): Long? {
        val seat = when (owner) {
            is BikeSeatEntity -> owner
            is ServerPlayer -> owner.vehicle as? BikeSeatEntity
            else -> owner?.vehicle as? BikeSeatEntity
        }
        return seat?.bodyId
    }

    private fun meleeDamage(player: ServerPlayer): Double {
        val base = player.getAttributeValue(Attributes.ATTACK_DAMAGE).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return base * MELEE_DAMAGE_SCALE
    }

    private fun sqrtDistance(distanceSqr: Double): Double {
        return kotlin.math.sqrt(distanceSqr.coerceAtLeast(0.0))
    }

    private data class DamageRayTarget(
        val vehicle: IVehicle,
        val hit: VehicleRayHit,
        val vehicleDistanceSqr: Double
    )
}
