package org.valkyrienskies.skyriders

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.shape.BodyShapeData
import org.valkyrienskies.core.api.bodies.shape.BoxBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.core.api.physics.RayCastResult
import org.valkyrienskies.core.api.physics.ShapeCastResult
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.impl.bodies.properties.BodyKinematicsImpl
import org.valkyrienskies.core.impl.bodies.properties.BodyTransformImpl
import org.valkyrienskies.physics_api.CollisionShapeReference
import org.valkyrienskies.physics_api.PhysicsBodyInertiaData
import org.valkyrienskies.physics_api.PhysicsBodyReference
import org.valkyrienskies.physics_api.PhysicsThreadPool
import org.valkyrienskies.physics_api.PoseVel
import org.valkyrienskies.physics_api_krunch.KrunchBootstrap
import org.valkyrienskies.physics_api_krunch.KrunchCollisionShapeFactory
import org.valkyrienskies.physics_api_krunch.KrunchPhysicsWorldSettings
import org.valkyrienskies.physics_api_krunch.PhysicsBackendType
import java.lang.reflect.Proxy

internal class NativeVehicleWorld(
    backend: PhysicsBackendType = PhysicsBackendType.KRUNCH_KONSTANT,
    subSteps: Int = when (backend) {
        PhysicsBackendType.KRUNCH_CLASSIC -> 20
        PhysicsBackendType.KRUNCH_KONSTANT -> 8
        PhysicsBackendType.KRUNCH_PHYSX -> 1
        PhysicsBackendType.KRUNCH_VOX3D -> 4
    }
) : AutoCloseable {
    init { KrunchBootstrap.loadNativeBinaries() }
    private val pool = KrunchBootstrap.createKrunchPhysicsThreadPool(1)
    private val world = KrunchBootstrap.createKrunchPhysicsWorld(pool)
    private val factory = KrunchCollisionShapeFactory()
    private val shapes = mutableListOf<CollisionShapeReference>()
    private val bodies = mutableMapOf<Int, Body>()
    var queryCount = 0
        private set

    init {
        KrunchBootstrap.setKrunchSettings(world, KrunchPhysicsWorldSettings(physicsBackendType = backend, subSteps = subSteps,
            positionIterations = 8, velocityIterations = 2, maxDePenetrationSpeed = 10.0))
    }

    val level: PhysLevel = proxy(PhysLevel::class.java) { name, args ->
        when (name) {
            "getDimension" -> "skyriders:native-test"
            "getBodyById" -> bodies[(args[0] as Long).toInt()]?.api
            "rayCast" -> {
                queryCount++
                val result = world.rayTraceWithVel(args[0] as Vector3dc, args[1] as Vector3dc, args[2] as Double,
                    *(args[3] as LongArray).map(Long::toInt).toIntArray())
                result?.let { RayCastResult(bodies.getValue(it.hitBodyId).api, it.distance, it.hitNormal,
                    it.velocity, it.angularVelocity, it.staticFrictionCoefficient, it.dynamicFrictionCoefficient,
                    it.restitutionCoefficient) }
            }
            "castSphere", "castBox" -> {
                queryCount++
                val hit = if (name == "castSphere") {
                    world.sweepSphere(args[0] as Vector3dc, args[1] as Double, args[2] as Vector3dc, args[3] as Double,
                        *(args[4] as LongArray).map(Long::toInt).toIntArray())
                } else {
                    world.sweepBox(args[0] as Vector3dc, args[1] as Quaterniondc, args[2] as Vector3dc,
                        args[3] as Vector3dc, args[4] as Double, *(args[5] as LongArray).map(Long::toInt).toIntArray())
                }
                hit?.let { ShapeCastResult(bodies.getValue(it.hitBodyId).api, it.segmentId, it.distance, it.point,
                    it.normal, it.velocity, it.angularVelocity, it.staticFriction, it.dynamicFriction, it.restitution,
                    it.initialOverlap, it.geometrySupported) }
            }
            else -> error("Unimplemented PhysLevel.$name")
        }
    }

    fun box(size: Vector3dc, position: Vector3dc, mass: Double = 1.0, static: Boolean = true,
            offset: Vector3dc = Vector3d(), rotation: Quaterniondc = Quaterniond()): Body {
        return compound(listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(size), position = offset, segmentId = 0)),
            position, mass, static, rotation)
    }

    fun compound(children: List<CompoundBodyShapeData.Child>, position: Vector3dc, mass: Double = 1.0,
                 static: Boolean = true, rotation: Quaterniondc = Quaterniond(), pivot: Vector3dc = Vector3d()): Body {
        val shape = factory.makeCompoundShapeReference().also(shapes::add)
        children.forEach { child ->
            val box = factory.makeBoxShapeReference((child.shape as BoxBodyShapeData).lengths).also(shapes::add)
            shape.makeNewSegment(child.segmentId, box, PoseVel.createPoseVel(child.position, child.rotation),
                child.collisionShapeScaling, child.collisionShapeOffset)
        }
        val native = world.createRigidBody(shape)
        native.inertiaData = PhysicsBodyInertiaData(1.0 / mass, Matrix3d().scale(1.0 / mass))
        native.poseVel = PoseVel.createPoseVel(position, rotation)
        native.collisionShapeOffset = Vector3d(pivot).negate()
        native.isStatic = static
        native.staticFrictionCoefficient = 0.25
        native.dynamicFrictionCoefficient = 0.25
        native.restitutionCoefficient = 0.04
        return Body(native, CompoundBodyShapeData(children), Vector3d(pivot)).also { bodies[native.physicsBodyId] = it }
    }

    fun step(dt: Double) {
        bodies.values.forEach { it.previous = it.current }
        pool.simulateWorlds(listOf(PhysicsThreadPool.WorldSimulationTask(world, Vector3d(0.0, -9.81, 0.0))), dt)
            .single().get()
        bodies.values.forEach(Body::refresh)
    }

    override fun close() {
        world.deletePhysicsWorldResources()
        shapes.asReversed().forEach { (it as AutoCloseable).close() }
        (pool as AutoCloseable).close()
    }

    inner class Body(val native: PhysicsBodyReference<*>, private val shape: BodyShapeData, private val pivot: Vector3d) {
        var current = snapshot()
            private set
        var previous = current
        private fun snapshot(): BodyKinematicsImpl {
            val pose = native.poseVel
            return BodyKinematicsImpl(pose.vel, pose.omega,
                BodyTransformImpl(pose.pos, pose.rot, Vector3d(1.0), Vector3d(pivot)))
        }
        fun refresh() { current = snapshot() }
        fun velocity(velocity: Vector3dc) {
            native.poseVel = native.poseVel.copy(vel = velocity)
            native.wakeUp()
            refresh()
        }
        fun angularVelocity(velocity: Vector3dc) {
            native.poseVel = native.poseVel.copy(omega = velocity)
            native.wakeUp()
            refresh()
        }
        val api: PhysVsBody = proxy(PhysVsBody::class.java) { name, args ->
            when (name) {
                "getId" -> native.physicsBodyId.toLong()
                "getKinematics" -> current
                "getPrevTickKinematics" -> previous
                "getCollisionShape" -> shape
                "isStatic", "getIsStatic" -> native.isStatic
                "applyWorldForce" -> {
                    val force = args[0] as Vector3dc
                    check(force.isFinite) { "Non-finite force: $force" }
                    val local = current.toModel.transformPosition(args[1] as Vector3dc, Vector3d()).sub(current.positionInModel)
                    native.addInvariantForceAtPosToNextPhysTick(local, force)
                    null
                }
                "applyWorldTorque" -> {
                    native.addInvariantTorqueToNextPhysTick(args[0] as Vector3dc)
                    null
                }
                else -> error("Unimplemented PhysVsBody.$name")
            }
        }
    }

    private fun <T> proxy(type: Class<T>, invoke: (String, Array<out Any>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "toString" -> type.simpleName
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args[0]
                else -> invoke(method.name, args ?: emptyArray())
            }
        }
    )
}
