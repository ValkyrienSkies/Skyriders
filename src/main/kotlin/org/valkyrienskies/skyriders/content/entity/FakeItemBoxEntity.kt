package org.valkyrienskies.skyriders.content.entity

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.sqrt

class FakeItemBoxEntity(type: EntityType<FakeItemBoxEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId = 0L
    private var landed = false

    init {
        blocksBuilding = false
        noPhysics = true
        noCulling = true
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide) return

        val serverLevel = level() as? ServerLevel ?: return
        if (tickCount > MAX_LIFETIME_TICKS) {
            discard()
            return
        }

        if (!landed) {
            tickToss(serverLevel)
        }

        findHitVehicle(serverLevel)?.let { target ->
            explode(serverLevel, position(), target)
        }
    }

    fun launch(origin: Vec3, velocity: Vec3, ownerBodyId: BodyId, yaw: Float) {
        this.ownerBodyId = ownerBodyId
        moveTo(origin.x, origin.y, origin.z, yaw, 0.0f)
        deltaMovement = velocity
        landed = false
    }

    override fun defineSynchedData() {
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
        landed = compound.getBoolean(LANDED_KEY)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
        compound.putBoolean(LANDED_KEY, landed)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = ClientboundAddEntityPacket(this)

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return distance < RENDER_DISTANCE * RENDER_DISTANCE
    }

    private fun tickToss(level: ServerLevel) {
        val start = position()
        val velocity = Vec3(
            deltaMovement.x * AIR_DRAG,
            deltaMovement.y * AIR_DRAG - GRAVITY,
            deltaMovement.z * AIR_DRAG
        )
        val next = start.add(velocity)
        val blockHit = level.clip(
            ClipContext(
                start,
                next,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
            )
        )
        if (blockHit.type != HitResult.Type.MISS) {
            setPos(blockHit.location.x, blockHit.location.y + LANDED_Y_OFFSET, blockHit.location.z)
            deltaMovement = Vec3.ZERO
            landed = true
            return
        }

        deltaMovement = velocity
        setPos(next.x, next.y, next.z)
    }

    private fun findHitVehicle(level: ServerLevel): IVehicle? {
        val shipWorld = level.shipWorld ?: return null
        val position = Vector3d(x, y, z)
        return VehicleManager.getVehicles(level).firstOrNull { vehicle ->
            if (vehicle.bodyId == ownerBodyId && tickCount < OWNER_GRACE_TICKS) return@firstOrNull false
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@firstOrNull false
            val radius = TRIGGER_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            body.kinematics.position.distanceSquared(position) <= radius * radius
        }
    }

    private fun explode(level: ServerLevel, position: Vec3, directTarget: IVehicle) {
        playExplosionSound(level, position)
        SkyridersMod.BAD_EXPLOSION_ENTITY.get().create(level)?.let { effect ->
            effect.moveTo(position.x, position.y + EXPLOSION_VISUAL_Y_OFFSET, position.z, random.nextFloat() * 360.0f, 0.0f)
            level.addFreshEntity(effect)
        }
        level.sendParticles(ParticleTypes.CLOUD, position.x, position.y, position.z, 18, 0.65, 0.45, 0.65, 0.08)

        val shipWorld = level.shipWorld
        val explosionPos = Vector3d(position.x, position.y, position.z)
        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@forEach
            val body = shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return@forEach
            val radius = BLAST_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            if (body.kinematics.position.distanceSquared(explosionPos) > radius * radius) return@forEach
            val duration = if (vehicle.bodyId == directTarget.bodyId) 2.25 else 1.45
            VehicleStatusEffects.applySpinOut(vehicle, duration = duration, yawSpeed = 6.0)
        }
        discard()
    }

    private fun playExplosionSound(level: ServerLevel, position: Vec3) {
        level.players().forEach { player ->
            val distance = player.position().distanceTo(position)
            if (distance > EXPLOSION_SOUND_RADIUS) return@forEach
            val falloff = (1.0 - distance / EXPLOSION_SOUND_RADIUS).coerceIn(0.0, 1.0)
            val volume = (0.35 + falloff * 0.75).toFloat()
            SkyridersNetwork.sendRocketExplosionSound(player, position, volume, 0.92f)
        }
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    companion object {
        private const val OWNER_BODY_ID_KEY = "OwnerBodyId"
        private const val LANDED_KEY = "Landed"
        private const val MAX_LIFETIME_TICKS = 20 * 45
        private const val OWNER_GRACE_TICKS = 18
        private const val AIR_DRAG = 0.96
        private const val GRAVITY = 0.045
        private const val LANDED_Y_OFFSET = 0.43
        private const val TRIGGER_RADIUS = 0.62
        private const val BLAST_RADIUS = 4.75
        private const val EXPLOSION_SOUND_RADIUS = 128.0
        private const val EXPLOSION_VISUAL_Y_OFFSET = 0.85
        private const val RENDER_DISTANCE = 192.0
    }
}
