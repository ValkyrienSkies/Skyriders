package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Vector3d
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.racing.RaceManager
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import org.valkyrienskies.skyriders.network.SkyridersNetwork

object BikeLifecycle {
    private const val VISUAL_SYNC_RADIUS = 128.0
    private const val VISUAL_SYNC_RADIUS_SQUARED = VISUAL_SYNC_RADIUS * VISUAL_SYNC_RADIUS
    private const val VISUAL_SYNC_INTERVAL_TICKS = 1L
    private const val IDLE_VISUAL_SYNC_INTERVAL_TICKS = 4L
    private const val ACTIVE_LINEAR_SPEED_SQUARED = 0.04
    private const val ACTIVE_ANGULAR_SPEED_SQUARED = 0.01
    private val pendingRestoreLevels = mutableSetOf<ServerLevel>()

    @SubscribeEvent
    fun onLevelLoad(event: LevelEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        pendingRestoreLevels.add(level)
    }

    @SubscribeEvent
    fun onLevelSave(event: LevelEvent.Save) {
        val level = event.level as? ServerLevel ?: return
        BikeSavedData.get(level).replaceFromManager(level)
        RaceManager.saveLevel(level)
    }

    @SubscribeEvent
    fun onLevelUnload(event: LevelEvent.Unload) {
        val level = event.level as? ServerLevel ?: return
        pendingRestoreLevels.remove(level)
        RaceManager.unloadLevel(level)
        BoostPadHandler.clear(level)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        if (pendingRestoreLevels.isNotEmpty()) {
            val iterator = pendingRestoreLevels.iterator()
            while (iterator.hasNext()) {
                val level = iterator.next()
                RaceManager.loadLevel(level)
                VehicleManager.restoreVehicles(level, BikeSavedData.get(level).records)
                syncLevel(level)
                iterator.remove()
            }
        }

        event.server.allLevels.forEach { level ->
            VehicleManager.tick(level.dimensionId)
            val vehicles = VehicleManager.getVehicles(level.dimensionId)
            BoostPadHandler.gameTick(level, vehicles)
            VehicleStatusEffects.gameTick(level, vehicles)
            VehicleImpactDamageHandler.tick(level, vehicles)
            VehicleDamage.tick(level, vehicles)
            RaceManager.tickLevel(level)
            if (level.gameTime % VISUAL_SYNC_INTERVAL_TICKS == 0L) {
                syncVisualState(level)
            }
        }
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        syncPlayer(player)
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        syncPlayer(player)
    }

    fun saveLevel(level: ServerLevel) {
        BikeSavedData.get(level).replaceFromManager(level)
        RaceManager.saveLevel(level)
    }

    fun syncLevel(level: ServerLevel) {
        val records = VehicleManager.getSaveRecords(level.dimensionId)
        level.players().forEach { player -> SkyridersNetwork.sendVehicleSync(player, records) }
    }

    fun syncPlayer(player: ServerPlayer) {
        val records = VehicleManager.getSaveRecords(player.level().dimensionId)
        SkyridersNetwork.sendVehicleSync(player, records)
    }

    private fun syncVisualState(level: ServerLevel) {
        val vehicles = VehicleManager.getVehicles(level.dimensionId)
        if (vehicles.isEmpty()) return
        val syncIdleThisTick = level.gameTime % IDLE_VISUAL_SYNC_INTERVAL_TICKS == 0L
        level.players().forEach { player ->
            val riddenBodyId = (player.vehicle as? BikeSeatEntity)?.bodyId
            val relevantVehicles = vehicles.filter { vehicle ->
                val ridden = vehicle.bodyId == riddenBodyId
                val near = ridden || isVehicleNearPlayer(vehicle, player)
                near && (ridden || syncIdleThisTick || isVehicleVisuallyActive(vehicle))
            }
            if (relevantVehicles.isNotEmpty()) {
                SkyridersNetwork.sendVehicleVisualState(player, relevantVehicles)
            }
        }
    }

    private fun isVehicleNearPlayer(vehicle: IVehicle, player: ServerPlayer): Boolean {
        val transform = try {
            vehicle.getRenderTransform()
        } catch (_: IllegalStateException) {
            null
        } ?: return false
        val position = transform.toWorld.transformPosition(Vector3d())
        if (!position.isFinite()) return false
        return player.distanceToSqr(position.x, position.y, position.z) <= VISUAL_SYNC_RADIUS_SQUARED
    }

    private fun isVehicleVisuallyActive(vehicle: IVehicle): Boolean {
        if (VehicleStatusEffects.isMoondropActive(vehicle)) return true
        if (isVehicleEngineOn(vehicle)) return true
        val kinematics = try {
            vehicle.level.shipWorld?.allBodies?.getById(vehicle.bodyId)?.kinematics
        } catch (_: IllegalStateException) {
            null
        } ?: return false
        return kinematics.velocity.lengthSquared() > ACTIVE_LINEAR_SPEED_SQUARED ||
            kinematics.angularVelocity.lengthSquared() > ACTIVE_ANGULAR_SPEED_SQUARED
    }

    private fun isVehicleEngineOn(vehicle: IVehicle): Boolean {
        return when (vehicle) {
            is IBike -> vehicle.state.engineOn
            is KartVehicle -> vehicle.kartState.engineOn
            is WheeledVehicle -> vehicle.wheeledState.engineOn
            else -> vehicle.vehicleState.engineOn
        }
    }

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }
}
