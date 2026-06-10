package org.valkyrienskies.skyriders.content

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
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
import org.valkyrienskies.skyriders.content.item.RaceFlagItem
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
        dispatchInteraction(player, target)
    }

    fun mountBike(
        player: ServerPlayer,
        bike: IBike,
        notifyPlayer: Boolean,
        interactionZoneId: String = VehicleInteractionDefinition.SEAT
    ): Boolean {
        return mountVehicle(player, bike, notifyPlayer, interactionZoneId)
    }

    fun mountVehicle(
        player: ServerPlayer,
        vehicle: IVehicle,
        notifyPlayer: Boolean,
        interactionZoneId: String = VehicleInteractionDefinition.SEAT
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (hoistedPlayersByBody.containsKey(vehicle.bodyId)) {
            if (notifyPlayer) {
                player.sendSystemMessage(Component.literal("That vehicle is being carried."))
            }
            return false
        }

        val transform = try {
            vehicle.getRenderTransform()
        } catch (ex: IllegalStateException) {
            if (notifyPlayer) {
                player.sendSystemMessage(Component.literal(ex.message ?: "Vehicle body is missing."))
            }
            return false
        }

        val seatDefinition = vehicle.vehicleDefinition.seats.firstOrNull { it.interactionZone == interactionZoneId }
            ?: vehicle.vehicleDefinition.seats.firstOrNull { it.id == VehicleInteractionDefinition.SEAT }
        val seatLocalPos = seatDefinition?.localPos ?: Vector3d(0.0, 0.35, 0.0)
        val seatWorld = transform.toWorld.transformPosition(Vector3d(seatLocalPos))
        val forward = transform.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        val yaw = Math.toDegrees(atan2(-forward.x, forward.z)).toFloat()
        val seat = SkyridersMod.BIKE_SEAT_ENTITY.get().create(level)
            ?: run {
                if (notifyPlayer) {
                    player.sendSystemMessage(Component.literal("Could not create bike seat entity."))
                }
                return false
        }

        seat.bodyId = vehicle.bodyId
        seat.seatId = seatDefinition?.id ?: VehicleInteractionDefinition.SEAT
        seat.moveTo(seatWorld.x, seatWorld.y, seatWorld.z, yaw, 0.0f)
        level.addFreshEntity(seat)
        player.startRiding(seat, true)
        alignPlayerToBike(player, yaw)
        vehicle.vehicleDefinition.interactions.zone(interactionZoneId)?.let { zone ->
            VehicleInteractionSounds.play(player, vehicle, zone, VehicleInteractionActions.MOUNT)
        }

        if (notifyPlayer) {
            player.sendSystemMessage(Component.literal("Mounted ${vehicle.vehicleDefinition.displayName} (${vehicle.id}) with VS body ${vehicle.bodyId}"))
        }
        return true
    }

    fun findBikeInLook(player: Player, range: Double = USE_RANGE): VehicleInteractionHit? {
        val start = player.getEyePosition(1.0f)
        val end = start.add(player.lookAngle.scale(range))
        return findBikeOnRay(player.level(), start, end)
    }

    fun findBikeOnRay(level: net.minecraft.world.level.Level, start: Vec3, end: Vec3): VehicleInteractionHit? {
        val hit = VehicleInteractionPicker.findVehicleOnRay(level, start, end) ?: return null
        return VehicleInteractionHit(
            vehicle = hit.vehicle,
            zone = hit.zone,
            zoneId = hit.zone.id,
            actions = hit.zone.actions,
            distanceSqr = hit.distanceSqr
        )
    }

    private fun dispatchInteraction(player: ServerPlayer, target: VehicleInteractionHit): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val actions = target.actions

        if (applyHeldRaceFlag(player, level, target.vehicle)) {
            return true
        }

        if (player.isShiftKeyDown && VehicleInteractionActions.PICK_UP in actions) {
            startHoisting(player, target.vehicle, target.zone)
            return true
        }

        if (VehiclePartInteractionHandlers.handle(player, target.vehicle, target.zone)) {
            return true
        }

        if (VehicleInteractionActions.MOUNT in actions) {
            return mountVehicle(player, target.vehicle, notifyPlayer = false, interactionZoneId = target.zoneId)
        }

        if (VehicleInteractionActions.ENGINE_TOGGLE in actions) {
            VehicleManager.toggleEngine(level, target.vehicle.bodyId)
            return true
        }

        return false
    }

    private fun applyHeldRaceFlag(player: ServerPlayer, level: ServerLevel, vehicle: IVehicle): Boolean {
        val hand = InteractionHand.entries.firstOrNull { hand ->
            player.getItemInHand(hand).item is RaceFlagItem
        } ?: return false
        val stack = player.getItemInHand(hand)
        val flag = stack.item as? RaceFlagItem ?: return false
        flag.applyToVehicle(level, player, vehicle, stack)
        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }
        return true
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

    private fun startHoisting(player: ServerPlayer, vehicle: IVehicle, zone: VehicleInteractionZone? = null) {
        if (player.vehicle is BikeSeatEntity) return
        if (hoistedPlayersByBody.containsKey(vehicle.bodyId)) return

        val level = player.level() as? ServerLevel ?: return
        val body = getServerBody(level, vehicle.bodyId) ?: return
        body.isStatic = true
        VehicleManager.clearInput(level.dimensionId, vehicle.bodyId)

        hoistedByPlayer[player.uuid] = HoistedBike(level.dimensionId, vehicle.bodyId)
        hoistedPlayersByBody[vehicle.bodyId] = player.uuid
        SkyridersNetwork.sendBikeHoistState(player, true, vehicle.bodyId)
        zone?.let { VehicleInteractionSounds.play(player, vehicle, it, VehicleInteractionActions.PICK_UP) }
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
        val vehicle = VehicleManager.getVehicle(level.dimensionId, hoisted.bodyId)
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
            val mass = vehicle?.vehicleDefinition?.body?.mass ?: 250.0
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

    data class VehicleInteractionHit(
        val vehicle: IVehicle,
        val zone: VehicleInteractionZone,
        val zoneId: String,
        val actions: Set<net.minecraft.resources.ResourceLocation>,
        val distanceSqr: Double
    )

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
