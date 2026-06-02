package org.valkyrienskies.skyriders.content

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.ServerBaseVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.max

object BikeInteractionHandler {
    private const val USE_RANGE = 5.0
    private const val HOIST_FORWARD_OFFSET = 0.45
    private const val HOIST_UP_OFFSET = 1.05
    private const val PLACE_UP_OFFSET = 0.45
    private const val TOSS_FORCE_DURATION = 0.12
    private const val TOSS_FORWARD_FORCE_PER_MASS = 45.0
    private const val TOSS_UP_FORCE_PER_MASS = 8.0

    private val hoistedByPlayer = ConcurrentHashMap<UUID, HoistedBike>()
    private val hoistedPlayersByBody = ConcurrentHashMap<BodyId, UUID>()
    private val pendingReleases = ConcurrentHashMap<BodyId, ReleasePose>()
    private val pendingTosses = ConcurrentHashMap<BodyId, PendingToss>()

    fun handleUse(player: ServerPlayer) {
        val level = player.level() as? ServerLevel ?: return
        val existingHoist = hoistedByPlayer[player.uuid]

        if (existingHoist != null) {
            if (player.isShiftKeyDown) {
                releaseHoistedBike(player, existingHoist)
            }
            return
        }

        val target = findBikeInLook(player, USE_RANGE) ?: return
        if (player.isShiftKeyDown) {
            startHoisting(player, target)
        } else {
            mountBike(player, target, notifyPlayer = false)
        }
    }

    fun mountBike(player: ServerPlayer, bike: IBike, notifyPlayer: Boolean): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (hoistedPlayersByBody.containsKey(bike.bodyId)) {
            if (notifyPlayer) {
                player.sendSystemMessage(Component.literal("That bike is being carried."))
            }
            return false
        }

        val transform = try {
            bike.getTransform()
        } catch (ex: IllegalStateException) {
            if (notifyPlayer) {
                player.sendSystemMessage(Component.literal(ex.message ?: "Bike body is missing."))
            }
            return false
        }

        val seatWorld = transform.toWorld.transformPosition(Vector3d(0.0, bike.getSeatOffset(), 0.0))
        val forward = transform.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        val yaw = Math.toDegrees(atan2(-forward.x, forward.z)).toFloat()
        val seat = SkyridersMod.BIKE_SEAT_ENTITY.get().create(level)
            ?: run {
                if (notifyPlayer) {
                    player.sendSystemMessage(Component.literal("Could not create bike seat entity."))
                }
                return false
            }

        seat.bodyId = bike.bodyId
        seat.moveTo(seatWorld.x, seatWorld.y, seatWorld.z, yaw, 0.0f)
        level.addFreshEntity(seat)
        player.startRiding(seat, true)
        alignPlayerToBike(player, yaw)

        if (notifyPlayer) {
            player.sendSystemMessage(Component.literal("Mounted ${bike.definition.displayName} (${bike.id}) with VS body ${bike.bodyId}"))
        }
        return true
    }

    fun findBikeInLook(player: Player, range: Double = USE_RANGE): IBike? {
        val start = player.getEyePosition(1.0f)
        val end = start.add(player.lookAngle.scale(range))
        return findBikeOnRay(player.level(), start, end)?.bike
    }

    fun findBikeOnRay(level: net.minecraft.world.level.Level, start: Vec3, end: Vec3): BikeRayHit? {
        return BikeManager.getBikes(level)
            .asSequence()
            .mapNotNull { bike -> hitBikeAabb(bike, start, end) }
            .minByOrNull(BikeRayHit::distanceSqr)
    }

    @SubscribeEvent
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val player = event.player as? ServerPlayer ?: return
        updateHoistedBike(player)
    }

    fun physTick(physLevel: PhysLevel, dt: Double) {
        hoistedByPlayer.values.forEach { hoisted ->
            if (hoisted.dimensionId != physLevel.dimension) return@forEach

            val body = physLevel.getBodyById(hoisted.bodyId) ?: return@forEach
            val pose = hoisted.pose
            body.isStatic = true
            body.setTransform(pose.position, pose.rotation, Vector3d(1.0, 1.0, 1.0))
        }

        val releaseIterator = pendingReleases.entries.iterator()
        while (releaseIterator.hasNext()) {
            val entry = releaseIterator.next()
            val release = entry.value
            if (release.dimensionId != physLevel.dimension) continue

            val body = physLevel.getBodyById(entry.key)
            if (body != null) {
                body.setTransform(release.position, release.rotation, Vector3d(1.0, 1.0, 1.0))
                body.isStatic = false
            }
            releaseIterator.remove()
        }

        val iterator = pendingTosses.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val toss = entry.value
            if (toss.dimensionId != physLevel.dimension) continue

            val body = physLevel.getBodyById(entry.key)
            if (body == null) {
                iterator.remove()
                continue
            }

            val force = Vector3d(toss.direction).mul(toss.force)
            body.applyWorldForce(force, body.kinematics.position)
            toss.remaining -= dt
            if (toss.remaining <= 0.0) {
                iterator.remove()
            }
        }
    }

    private fun startHoisting(player: ServerPlayer, bike: IBike) {
        if (player.vehicle is BikeSeatEntity) return
        if (hoistedPlayersByBody.containsKey(bike.bodyId)) return

        val level = player.level() as? ServerLevel ?: return
        val body = getServerBody(level, bike.bodyId) ?: return
        body.isStatic = true
        BikeManager.updateInput(level.dimensionId, bike.bodyId) { BikeInput.EMPTY }

        hoistedByPlayer[player.uuid] = HoistedBike(level.dimensionId, bike.bodyId)
        hoistedPlayersByBody[bike.bodyId] = player.uuid
        SkyridersNetwork.sendBikeHoistState(player, true, bike.bodyId)
        updateHoistedBike(player)
    }

    private fun updateHoistedBike(player: ServerPlayer) {
        val hoisted = hoistedByPlayer[player.uuid] ?: return
        val level = player.level() as? ServerLevel ?: return clearHoist(player.uuid, hoisted.bodyId)
        if (level.dimensionId != hoisted.dimensionId || player.isRemoved || player.isDeadOrDying) {
            clearHoist(player.uuid, hoisted.bodyId)
            return
        }

        val eye = player.eyePosition
        val look = player.lookAngle
        val pos = Vector3d(
            eye.x + look.x * HOIST_FORWARD_OFFSET,
            eye.y + HOIST_UP_OFFSET,
            eye.z + look.z * HOIST_FORWARD_OFFSET
        )
        hoisted.pose = CarryPose(pos, carriedRotation(player))
    }

    private fun releaseHoistedBike(player: ServerPlayer, hoisted: HoistedBike) {
        val level = player.level() as? ServerLevel ?: return clearHoist(player.uuid, hoisted.bodyId)
        val body = getServerBody(level, hoisted.bodyId) ?: return clearHoist(player.uuid, hoisted.bodyId)
        val bike = BikeManager.getBike(level.dimensionId, hoisted.bodyId)
        val hit = player.pick(USE_RANGE, 1.0f, false)

        if (hit.type == HitResult.Type.BLOCK && hit is BlockHitResult) {
            val loc = hit.location
            val normal = Vec3.atLowerCornerOf(hit.direction.normal)
            val pos = Vector3d(
                loc.x + normal.x * 0.25,
                loc.y + normal.y * 0.25 + PLACE_UP_OFFSET,
                loc.z + normal.z * 0.25
            )
            pendingReleases[hoisted.bodyId] = ReleasePose(level.dimensionId, pos, carriedRotation(player))
        } else {
            val eye = player.eyePosition
            val look = player.lookAngle.normalize()
            val pos = Vector3d(
                eye.x + look.x * 1.15,
                eye.y - 0.2 + look.y * 0.5,
                eye.z + look.z * 1.15
            )
            pendingReleases[hoisted.bodyId] = ReleasePose(level.dimensionId, pos, carriedRotation(player))
            val mass = bike?.config?.mass ?: 250.0
            val direction = Vector3d(look.x, max(look.y, 0.0) + 0.18, look.z).normalize()
            pendingTosses[hoisted.bodyId] = PendingToss(
                dimensionId = level.dimensionId,
                direction = direction,
                force = mass * (TOSS_FORWARD_FORCE_PER_MASS + TOSS_UP_FORCE_PER_MASS),
                remaining = TOSS_FORCE_DURATION
            )
        }

        SkyridersNetwork.sendBikeHoistState(player, false)
        clearHoist(player.uuid, hoisted.bodyId)
    }

    private fun clearHoist(playerId: UUID, bodyId: BodyId) {
        hoistedByPlayer.remove(playerId)
        hoistedPlayersByBody.remove(bodyId)
    }

    private fun carriedRotation(player: ServerPlayer): Quaterniond {
        return Quaterniond().rotateY(Math.toRadians(90.0 - player.yRot.toDouble()))
    }

    private fun alignPlayerToBike(player: ServerPlayer, yaw: Float) {
        player.setYRot(yaw)
        player.yHeadRot = yaw
        player.yHeadRotO = yaw
        player.yBodyRot = yaw
        player.yBodyRotO = yaw
    }

    private fun getServerBody(level: ServerLevel, bodyId: BodyId): ServerBaseVsBody? {
        val shipWorld = vsApi.getServerShipWorld(level.server) ?: return null
        return shipWorld.allBodies.getById(bodyId) as? ServerBaseVsBody
    }

    private fun hitBikeAabb(bike: IBike, start: Vec3, end: Vec3): BikeRayHit? {
        val transform = try {
            bike.getRenderTransform()
        } catch (ex: IllegalStateException) {
            return null
        }
        val config = bike.config
        val center = transform.toWorld.transformPosition(Vector3d(config.collisionBoxOffset))
        val halfX = max(config.collisionBoxSize.x * 0.5, config.wheelWidth * 0.5) + 0.25
        val halfY = max(config.collisionBoxSize.y * 0.5, config.wheelRadius + config.suspensionRestLength * 0.5) + 0.35
        val frontZ = config.frontWheelLocalPos.z + config.wheelRadius
        val rearZ = -config.rearWheelLocalPos.z + config.wheelRadius
        val halfZ = max(config.collisionBoxSize.z * 0.5, max(frontZ, rearZ)) + 0.25
        val aabb = AABB(
            center.x - halfX,
            center.y - halfY,
            center.z - halfZ,
            center.x + halfX,
            center.y + halfY,
            center.z + halfZ
        )
        val hit = aabb.clip(start, end).orElse(null) ?: return null
        return BikeRayHit(bike, hit.distanceToSqr(start))
    }

    data class BikeRayHit(val bike: IBike, val distanceSqr: Double)

    private data class HoistedBike(
        val dimensionId: org.valkyrienskies.core.api.world.properties.DimensionId,
        val bodyId: BodyId,
        @Volatile var pose: CarryPose = CarryPose(Vector3d(), Quaterniond())
    )

    private data class CarryPose(
        val position: Vector3dc,
        val rotation: org.joml.Quaterniondc
    )

    private data class ReleasePose(
        val dimensionId: org.valkyrienskies.core.api.world.properties.DimensionId,
        val position: Vector3dc,
        val rotation: org.joml.Quaterniondc
    )

    private data class PendingToss(
        val dimensionId: org.valkyrienskies.core.api.world.properties.DimensionId,
        val direction: Vector3dc,
        val force: Double,
        var remaining: Double
    )
}
