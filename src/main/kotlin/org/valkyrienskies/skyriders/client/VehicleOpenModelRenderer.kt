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
    private val models = HashMap<ResourceLocation, OpenModel?>()
    private val faceNames = listOf("north", "east", "south", "west", "up", "down")

    fun renderIfNeeded(
        modelLocation: ResourceLocation,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ): Boolean {
        val model = getModel(modelLocation) ?: return false
        if (!model.hasRotatedComponents) return false

        val minecraft = Minecraft.getInstance()
        val spriteLookup = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
        val consumer = bufferSource.getBuffer(RenderType.cutout())
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

    fun renderTexturedIfNeeded(
        modelLocation: ResourceLocation,
        textureLocation: ResourceLocation,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ): Boolean {
        val model = getModel(modelLocation) ?: return false

        val consumer = bufferSource.getBuffer(RenderType.entityCutout(textureLocation))
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
        var hasRotatedComponents = false
        json.getAsJsonArray("components")?.forEach { element ->
            val component = element.asJsonObject
            val from = component.getVector3d("from")
            val to = component.getVector3d("to")
            val transform = component.transform()
            if (transform.usesRotatedField) {
                hasRotatedComponents = true
            }
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
            hasRotatedComponents = hasRotatedComponents,
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
            return ComponentTransform.Euler(origin, rotation, usesRotatedField = true)
        }

        val rotation = getAsJsonObject("rotation") ?: return ComponentTransform.None
        val angle = rotation.getDouble("angle")
        if (angle == 0.0) return ComponentTransform.None

        val origin = rotation.getVector3d("origin")
        return when (rotation.getString("axis")) {
            "x" -> ComponentTransform.Euler(origin, Vector3d(angle, 0.0, 0.0), usesRotatedField = false)
            "y" -> ComponentTransform.Euler(origin, Vector3d(0.0, angle, 0.0), usesRotatedField = false)
            "z" -> ComponentTransform.Euler(origin, Vector3d(0.0, 0.0, angle), usesRotatedField = false)
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
            Vector3f(maxU.toFloat(), maxV.toFloat(), 0.0f),
            Vector3f(minU.toFloat(), maxV.toFloat(), 0.0f),
            Vector3f(minU.toFloat(), minV.toFloat(), 0.0f),
            Vector3f(maxU.toFloat(), minV.toFloat(), 0.0f)
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
            minU = uv[0].asDouble,
            minV = uv[1].asDouble,
            maxU = uv[2].asDouble,
            maxV = uv[3].asDouble
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
        val hasRotatedComponents: Boolean,
        val faces: List<OpenFace>
    )

    private data class OpenFace(
        val vertices: Array<Vector3f>,
        val normal: Vector3f,
        val uvs: Array<Vector3f>,
        val texture: ResourceLocation
    )

    private data class FaceUv(
        val minU: Double,
        val minV: Double,
        val maxU: Double,
        val maxV: Double
    )

    private sealed interface ComponentTransform {
        val usesRotatedField: Boolean

        data object None : ComponentTransform {
            override val usesRotatedField: Boolean = false
        }

        data class Euler(
            val origin: Vector3d,
            val degrees: Vector3d,
            override val usesRotatedField: Boolean
        ) : ComponentTransform
    }
}
