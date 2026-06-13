package org.valkyrienskies.skyriders.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import org.joml.Vector3d
import org.joml.Vector3f
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.sin

object VehicleOpenModelRenderer {
    val BLOCK_ATLAS_NO_CULL_RENDER_TYPE: RenderType = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS)

    private val models = HashMap<ResourceLocation, OpenModel?>()
    private val faceNames = listOf("north", "east", "south", "west", "up", "down")
    private val damageCrackRenderTypes = (0..9).map { stage ->
        RenderType.entityTranslucent(ResourceLocation("textures/block/destroy_stage_$stage.png"))
    }

    fun renderIfNeeded(
        modelLocation: ResourceLocation,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        forceRender: Boolean = false
    ): Boolean {
        val model = getModel(modelLocation) ?: return false
        if (!forceRender && !model.requiresCustomTransforms) return false

        val minecraft = Minecraft.getInstance()
        val spriteLookup = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
        val consumer = bufferSource.getBuffer(BLOCK_ATLAS_NO_CULL_RENDER_TYPE)
        val pose = poseStack.last()

        model.faces.forEach { face ->
            val sprite = spriteLookup.apply(face.texture)
            val shade = faceShade(face.normal)
            val color = (shade * 255.0f).toInt().coerceIn(0, 255)
            for (i in 0 until 4) {
                val vertex = face.vertices[i]
                val uv = face.uvs[i]
                consumer.vertex(pose.pose(), vertex.x() / 16.0f, vertex.y() / 16.0f, vertex.z() / 16.0f)
                    .color(color, color, color, 255)
                    .uv(sprite.getU(uv.x.toDouble()), sprite.getV(uv.y.toDouble()))
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight)
                    .normal(pose.normal(), face.normal.x(), face.normal.y(), face.normal.z())
                    .endVertex()
            }
        }
        return true
    }

    fun renderDamageCracksIfNeeded(
        modelLocation: ResourceLocation,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        zones: List<DamageCrackZone>,
        modelOffset: Vector3d,
        modelScale: Double,
        modelYawRad: Double
    ): Boolean {
        if (zones.isEmpty()) return false
        val model = getModel(modelLocation) ?: return false
        val pose = poseStack.last()
        var rendered = false
        zones.forEach { zone ->
            val stage = (zone.damageFraction.coerceIn(0.0, 1.0) * 9.0).toInt().coerceIn(0, 9)
            val alpha = (0.28f + zone.damageFraction.toFloat() * 0.45f).coerceIn(0.28f, 0.73f)
            val consumer = bufferSource.getBuffer(damageCrackRenderTypes[stage])
            model.faces.forEach { face ->
                if (!faceIntersectsZone(face, zone, modelOffset, modelScale, modelYawRad)) return@forEach
                renderCrackFace(face, poseStack, consumer, pose, packedLight, alpha)
                rendered = true
            }
        }
        return rendered
    }

    fun renderWholeModelDamageCracksIfNeeded(
        modelLocation: ResourceLocation,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        damageFraction: Double
    ): Boolean {
        if (damageFraction < MIN_CRACK_DAMAGE_FRACTION) return false
        val model = getModel(modelLocation) ?: return false
        val pose = poseStack.last()
        val stage = (damageFraction.coerceIn(0.0, 1.0) * 9.0).toInt().coerceIn(0, 9)
        val alpha = (0.28f + damageFraction.toFloat() * 0.45f).coerceIn(0.28f, 0.73f)
        val consumer = bufferSource.getBuffer(damageCrackRenderTypes[stage])
        model.faces.forEach { face ->
            renderCrackFace(face, poseStack, consumer, pose, packedLight, alpha)
        }
        return model.faces.isNotEmpty()
    }

    fun endDamageCrackBatches(bufferSource: MultiBufferSource.BufferSource) {
        damageCrackRenderTypes.forEach(bufferSource::endBatch)
    }

    fun renderTexturedIfNeeded(
        modelLocation: ResourceLocation,
        textureLocation: ResourceLocation,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ): Boolean {
        val model = getModel(modelLocation) ?: return false

        val consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(textureLocation))
        val pose = poseStack.last()
        model.faces.forEach { face ->
            val shade = faceShade(face.normal)
            val color = (shade * 255.0f).toInt().coerceIn(0, 255)
            for (i in 0 until 4) {
                val vertex = face.vertices[i]
                val uv = face.uvs[i]
                consumer.vertex(pose.pose(), vertex.x() / 16.0f, vertex.y() / 16.0f, vertex.z() / 16.0f)
                    .color(color, color, color, 255)
                    .uv((uv.x() / model.textureWidth).toFloat(), (uv.y() / model.textureHeight).toFloat())
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight)
                    .normal(pose.normal(), face.normal.x(), face.normal.y(), face.normal.z())
                    .endVertex()
            }
        }
        return true
    }

    private fun faceShade(normal: Vector3f): Float {
        val up = normal.y().coerceIn(-1.0f, 1.0f)
        val horizontal = (1.0f - kotlin.math.abs(up)).coerceIn(0.0f, 1.0f)
        val sideDirection = (normal.x() * 0.35f + normal.z() * 0.18f).coerceIn(-0.35f, 0.35f)
        return (0.74f + up * 0.18f + horizontal * sideDirection).coerceIn(0.56f, 1.0f)
    }

    private fun renderCrackFace(
        face: OpenFace,
        poseStack: PoseStack,
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose,
        packedLight: Int,
        alpha: Float
    ) {
        val uvs = crackUvs()
        for (i in 0 until 4) {
            val vertex = face.vertices[i]
            val uv = uvs[i]
            val offsetX = face.normal.x() * CRACK_SURFACE_OFFSET_MODEL_UNITS
            val offsetY = face.normal.y() * CRACK_SURFACE_OFFSET_MODEL_UNITS
            val offsetZ = face.normal.z() * CRACK_SURFACE_OFFSET_MODEL_UNITS
            consumer.vertex(
                pose.pose(),
                (vertex.x() + offsetX) / 16.0f,
                (vertex.y() + offsetY) / 16.0f,
                (vertex.z() + offsetZ) / 16.0f
            )
                .color(1.0f, 1.0f, 1.0f, alpha)
                .uv(uv.x(), uv.y())
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(pose.normal(), face.normal.x(), face.normal.y(), face.normal.z())
                .endVertex()
        }
    }

    private fun faceIntersectsZone(
        face: OpenFace,
        zone: DamageCrackZone,
        modelOffset: Vector3d,
        modelScale: Double,
        modelYawRad: Double
    ): Boolean {
        if (face.vertices.any { vertex -> pointInsideZone(modelVertexToVehicleLocal(vertex, modelOffset, modelScale, modelYawRad), zone) }) {
            return true
        }
        val center = Vector3d()
        face.vertices.forEach { vertex ->
            center.add(vertex.x().toDouble(), vertex.y().toDouble(), vertex.z().toDouble())
        }
        center.div(face.vertices.size.toDouble())
        return pointInsideZone(modelVertexToVehicleLocal(Vector3f(center.x.toFloat(), center.y.toFloat(), center.z.toFloat()), modelOffset, modelScale, modelYawRad), zone)
    }

    private fun modelVertexToVehicleLocal(
        vertex: Vector3f,
        modelOffset: Vector3d,
        modelScale: Double,
        modelYawRad: Double
    ): Vector3d {
        val local = Vector3d(
            vertex.x().toDouble() / 16.0 * modelScale + modelOffset.x,
            vertex.y().toDouble() / 16.0 * modelScale + modelOffset.y,
            vertex.z().toDouble() / 16.0 * modelScale + modelOffset.z
        )
        if (modelYawRad.isFinite() && modelYawRad != 0.0) {
            local.rotateY(modelYawRad)
        }
        return local
    }

    private fun pointInsideZone(point: Vector3d, zone: DamageCrackZone): Boolean {
        val halfX = zone.size.x * 0.5 + ZONE_MATCH_PADDING
        val halfY = zone.size.y * 0.5 + ZONE_MATCH_PADDING
        val halfZ = zone.size.z * 0.5 + ZONE_MATCH_PADDING
        return point.x >= zone.center.x - halfX &&
            point.x <= zone.center.x + halfX &&
            point.y >= zone.center.y - halfY &&
            point.y <= zone.center.y + halfY &&
            point.z >= zone.center.z - halfZ &&
            point.z <= zone.center.z + halfZ
    }

    private fun crackUvs(): Array<Vector3f> {
        return arrayOf(
            Vector3f(0.0f, 1.0f, 0.0f),
            Vector3f(1.0f, 1.0f, 0.0f),
            Vector3f(1.0f, 0.0f, 0.0f),
            Vector3f(0.0f, 0.0f, 0.0f)
        )
    }

    private fun getModel(modelLocation: ResourceLocation): OpenModel? {
        return models.getOrPut(modelLocation) {
            loadModel(modelLocation)
        }
    }

    private fun loadModel(modelLocation: ResourceLocation): OpenModel? {
        val minecraft = Minecraft.getInstance()
        val resourceLocation = ResourceLocation(modelLocation.namespace, "models/${modelLocation.path}.json")
        val resource = minecraft.resourceManager.getResource(resourceLocation).orElse(null) ?: return null
        val json = resource.open().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }
        if (json.getString("loader") != "framework:open_model") return null

        val textures = json.getAsJsonObject("textures") ?: return null
        val textureAliases = textures.entrySet().associate { (key, value) ->
            key to ResourceLocation(value.asString)
        }

        val faces = ArrayList<OpenFace>()
        var requiresCustomTransforms = false
        json.getAsJsonArray("components")?.forEach { element ->
            val component = element.asJsonObject
            requiresCustomTransforms = requiresCustomTransforms || component.has("rotated")
            val from = component.getVector3d("from")
            val to = component.getVector3d("to")
            val transform = component.transform()
            val componentFaces = component.getAsJsonObject("faces") ?: return@forEach
            faceNames.forEach { faceName ->
                val face = componentFaces.getAsJsonObject(faceName) ?: return@forEach
                val texture = face.texture(textureAliases) ?: return@forEach
                val uv = face.getUv()
                faces += buildFace(faceName, from, to, transform, uv, texture)
            }
        }

        val textureSize = json.getAsJsonArray("texture_size")
        return OpenModel(
            textureWidth = textureSize?.get(0)?.asDouble ?: 16.0,
            textureHeight = textureSize?.get(1)?.asDouble ?: 16.0,
            requiresCustomTransforms = requiresCustomTransforms,
            faces = faces
        )
    }

    private fun buildFace(
        faceName: String,
        from: Vector3d,
        to: Vector3d,
        transform: ComponentTransform,
        uv: FaceUv,
        texture: ResourceLocation
    ): OpenFace {
        val x1 = from.x
        val y1 = from.y
        val z1 = from.z
        val x2 = to.x
        val y2 = to.y
        val z2 = to.z
        val vertices = when (faceName) {
            "north" -> arrayOf(
                Vector3d(x2, y1, z1),
                Vector3d(x1, y1, z1),
                Vector3d(x1, y2, z1),
                Vector3d(x2, y2, z1)
            )
            "south" -> arrayOf(
                Vector3d(x1, y1, z2),
                Vector3d(x2, y1, z2),
                Vector3d(x2, y2, z2),
                Vector3d(x1, y2, z2)
            )
            "west" -> arrayOf(
                Vector3d(x1, y1, z1),
                Vector3d(x1, y1, z2),
                Vector3d(x1, y2, z2),
                Vector3d(x1, y2, z1)
            )
            "east" -> arrayOf(
                Vector3d(x2, y1, z2),
                Vector3d(x2, y1, z1),
                Vector3d(x2, y2, z1),
                Vector3d(x2, y2, z2)
            )
            "up" -> arrayOf(
                Vector3d(x1, y2, z2),
                Vector3d(x2, y2, z2),
                Vector3d(x2, y2, z1),
                Vector3d(x1, y2, z1)
            )
            "down" -> arrayOf(
                Vector3d(x1, y1, z1),
                Vector3d(x2, y1, z1),
                Vector3d(x2, y1, z2),
                Vector3d(x1, y1, z2)
            )
            else -> emptyArray()
        }.map { transform.apply(it) }

        val normal = vertices.normal()
        return OpenFace(
            vertices = vertices.map { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }.toTypedArray(),
            normal = normal,
            uvs = uv.vertices(),
            texture = texture
        )
    }

    private fun JsonObject.transform(): ComponentTransform {
        val rotated = getAsJsonArray("rotated")
        if (rotated != null) {
            val rotation = getVector3d("rotated")
            val origin = getAsJsonObject("rotation")?.getVector3d("origin") ?: Vector3d()
            return ComponentTransform.Euler(origin, rotation)
        }

        val rotation = getAsJsonObject("rotation") ?: return ComponentTransform.None
        val angle = rotation.getDouble("angle")
        if (angle == 0.0) return ComponentTransform.None

        val origin = rotation.getVector3d("origin")
        return when (rotation.getString("axis")) {
            "x" -> ComponentTransform.Euler(origin, Vector3d(angle, 0.0, 0.0))
            "y" -> ComponentTransform.Euler(origin, Vector3d(0.0, angle, 0.0))
            "z" -> ComponentTransform.Euler(origin, Vector3d(0.0, 0.0, angle))
            else -> ComponentTransform.None
        }
    }

    private fun ComponentTransform.apply(vertex: Vector3d): Vector3d {
        return when (this) {
            ComponentTransform.None -> vertex
            is ComponentTransform.Euler -> applyEuler(vertex, origin, degrees)
        }
    }

    private fun applyEuler(vertex: Vector3d, origin: Vector3d, degrees: Vector3d): Vector3d {
        var x = vertex.x - origin.x
        var y = vertex.y - origin.y
        var z = vertex.z - origin.z

        val rx = Math.toRadians(degrees.x)
        val ry = Math.toRadians(degrees.y)
        val rz = Math.toRadians(degrees.z)

        if (rx != 0.0) {
            val c = cos(rx)
            val s = sin(rx)
            val nextY = y * c - z * s
            val nextZ = y * s + z * c
            y = nextY
            z = nextZ
        }
        if (ry != 0.0) {
            val c = cos(ry)
            val s = sin(ry)
            val nextX = x * c + z * s
            val nextZ = -x * s + z * c
            x = nextX
            z = nextZ
        }
        if (rz != 0.0) {
            val c = cos(rz)
            val s = sin(rz)
            val nextX = x * c - y * s
            val nextY = x * s + y * c
            x = nextX
            y = nextY
        }

        return Vector3d(x + origin.x, y + origin.y, z + origin.z)
    }

    private fun List<Vector3d>.normal(): Vector3f {
        if (size < 3) return Vector3f(0.0f, 1.0f, 0.0f)
        val edgeA = Vector3d(this[1]).sub(this[0])
        val edgeB = Vector3d(this[2]).sub(this[1])
        val normal = edgeA.cross(edgeB)
        if (!normal.x.isFinite() || !normal.y.isFinite() || !normal.z.isFinite() || normal.lengthSquared() < 1.0e-8) {
            return Vector3f(0.0f, 1.0f, 0.0f)
        }
        normal.normalize()
        return Vector3f(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
    }

    private fun FaceUv.vertices(): Array<Vector3f> {
        return arrayOf(
            Vector3f(u2.toFloat(), v2.toFloat(), 0.0f),
            Vector3f(u1.toFloat(), v2.toFloat(), 0.0f),
            Vector3f(u1.toFloat(), v1.toFloat(), 0.0f),
            Vector3f(u2.toFloat(), v1.toFloat(), 0.0f)
        )
    }

    private fun JsonObject.texture(textureAliases: Map<String, ResourceLocation>): ResourceLocation? {
        val textureName = getString("texture")
        return if (textureName.startsWith("#")) {
            textureAliases[textureName.substring(1)]
        } else {
            ResourceLocation.tryParse(textureName)
        }
    }

    private fun JsonObject.getUv(): FaceUv {
        val uv = getAsJsonArray("uv")
        return FaceUv(
            u1 = uv[0].asDouble,
            v1 = uv[1].asDouble,
            u2 = uv[2].asDouble,
            v2 = uv[3].asDouble
        )
    }

    private fun JsonObject.getVector3d(name: String): Vector3d {
        val array = getAsJsonArray(name)
        return Vector3d(array[0].asDouble, array[1].asDouble, array[2].asDouble)
    }

    private fun JsonObject.getDouble(name: String): Double {
        return get(name)?.asDouble ?: 0.0
    }

    private fun JsonObject.getString(name: String): String {
        return get(name)?.asString.orEmpty()
    }

    private data class OpenModel(
        val textureWidth: Double,
        val textureHeight: Double,
        val requiresCustomTransforms: Boolean,
        val faces: List<OpenFace>
    )

    private data class OpenFace(
        val vertices: Array<Vector3f>,
        val normal: Vector3f,
        val uvs: Array<Vector3f>,
        val texture: ResourceLocation
    )

    private data class FaceUv(
        val u1: Double,
        val v1: Double,
        val u2: Double,
        val v2: Double
    )

    private sealed interface ComponentTransform {
        data object None : ComponentTransform

        data class Euler(
            val origin: Vector3d,
            val degrees: Vector3d
        ) : ComponentTransform
    }

    data class DamageCrackZone(
        val center: Vector3d,
        val size: Vector3d,
        val damageFraction: Double
    )

    const val MIN_CRACK_DAMAGE_FRACTION = 0.08
    private const val ZONE_MATCH_PADDING = 0.05
    private const val CRACK_SURFACE_OFFSET_MODEL_UNITS = 0.035f
}
