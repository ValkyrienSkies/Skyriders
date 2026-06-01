package org.valkyrienskies.skyriders.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikeManager

object SkyridersCommands {
    fun register(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("skyrider")
                .requires { source -> source.hasPermission(2) }
                .then(
                    literal("summon")
                        .then(
                            argument("bike", StringArgumentType.word())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggest(bikeIdSuggestions(), builder)
                                }
                                .then(
                                    argument("pos", Vec3Argument.vec3())
                                        .executes { ctx ->
                                            summonBike(
                                                ctx.source,
                                                StringArgumentType.getString(ctx, "bike"),
                                                Vec3Argument.getVec3(ctx, "pos")
                                            )
                                        }
                                )
                        )
                )
                .then(
                    literal("input")
                        .then(
                            argument("bodyId", LongArgumentType.longArg())
                                .then(inputSetter("steer", -1.0, 1.0) { input, value -> input.copy(steer = value) })
                                .then(inputSetter("throttle", -1.0, 1.0) { input, value -> input.copy(throttle = value) })
                                .then(inputSetter("brake", 0.0, 1.0) { input, value -> input.copy(brake = value) })
                                .then(
                                    literal("reset")
                                        .executes { ctx ->
                                            setInput(
                                                ctx.source,
                                                LongArgumentType.getLong(ctx, "bodyId"),
                                                BikeInput.EMPTY
                                            )
                                        }
                                )
                        )
                )
                .then(
                    literal("list")
                        .executes { ctx -> listBikes(ctx.source) }
                )
                .then(
                    literal("remove")
                        .then(
                            argument("bodyId", LongArgumentType.longArg())
                                .executes { ctx ->
                                    removeBike(ctx.source, LongArgumentType.getLong(ctx, "bodyId"))
                                }
                        )
                )
        )
    }

    private fun bikeIdSuggestions(): Iterable<String> {
        return BikeManager.registeredBikeIds.flatMap { id ->
            if (id.namespace == SkyridersMod.MOD_ID) {
                listOf(id.path, id.toString())
            } else {
                listOf(id.toString())
            }
        }
    }

    private fun parseBikeId(input: String): ResourceLocation? {
        return if (ResourceLocation.isValidResourceLocation(input)) {
            if (':' in input) ResourceLocation(input) else ResourceLocation(SkyridersMod.MOD_ID, input)
        } else {
            null
        }
    }

    private fun inputSetter(
        name: String,
        min: Double,
        max: Double,
        update: (BikeInput, Double) -> BikeInput
    ) = literal(name)
        .then(
            argument("value", DoubleArgumentType.doubleArg(min, max))
                .executes { ctx ->
                    val value = DoubleArgumentType.getDouble(ctx, "value")
                    updateInput(
                        ctx.source,
                        LongArgumentType.getLong(ctx, "bodyId"),
                        name
                    ) { input -> update(input, value) }
                }
        )

    private fun summonBike(source: CommandSourceStack, bikeIdInput: String, pos: Vec3): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bikes can only be summoned on the server."))
                return 0
            }

        val bikeId = parseBikeId(bikeIdInput)
            ?: run {
                source.sendFailure(Component.literal("Invalid bike id: $bikeIdInput"))
                return 0
            }

        val bike = try {
            BikeManager.createBike(bikeId, level, Vector3d(pos.x, pos.y, pos.z))
        } catch (ex: IllegalArgumentException) {
            source.sendFailure(Component.literal(ex.message ?: "Unknown bike id: $bikeId"))
            return 0
        } catch (ex: IllegalStateException) {
            source.sendFailure(Component.literal(ex.message ?: "VS body world is not ready."))
            return 0
        }

        source.sendSuccess(
            { Component.literal("Summoned ${bike.id} as VS body ${bike.bodyId}") },
            true
        )
        return 1
    }

    private fun updateInput(
        source: CommandSourceStack,
        bodyId: BodyId,
        label: String,
        updater: (BikeInput) -> BikeInput
    ): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bike input can only be set on the server."))
                return 0
            }

        val input = BikeManager.updateInput(level.dimensionId, bodyId, updater)
            ?: run {
                source.sendFailure(Component.literal("No bike with VS body id $bodyId in this dimension."))
                return 0
            }

        source.sendSuccess(
            { Component.literal("Set $label for bike $bodyId: steer=${input.steer}, throttle=${input.throttle}, brake=${input.brake}") },
            false
        )
        return 1
    }

    private fun setInput(source: CommandSourceStack, bodyId: BodyId, input: BikeInput): Int {
        return updateInput(source, bodyId, "input") { input }
    }

    private fun listBikes(source: CommandSourceStack): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bikes can only be listed on the server."))
                return 0
            }

        val bikes = BikeManager.getBikes(level.dimensionId)
        if (bikes.isEmpty()) {
            source.sendSuccess({ Component.literal("No bikes in this dimension.") }, false)
            return 1
        }

        val message = bikes.joinToString(separator = "\n") { bike ->
            val input = BikeManager.getInput(level.dimensionId, bike.bodyId)
            "${bike.bodyId}: ${bike.id} input[steer=${input.steer}, throttle=${input.throttle}, brake=${input.brake}]"
        }
        source.sendSuccess({ Component.literal(message) }, false)
        return bikes.size
    }

    private fun removeBike(source: CommandSourceStack, bodyId: BodyId): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bikes can only be removed on the server."))
                return 0
            }

        val bike = try {
            BikeManager.removeBike(level, bodyId)
        } catch (ex: IllegalStateException) {
            source.sendFailure(Component.literal(ex.message ?: "VS body world is not ready."))
            return 0
        }

        if (bike == null) {
            source.sendFailure(Component.literal("No bike with VS body id $bodyId in this dimension."))
            return 0
        }

        source.sendSuccess({ Component.literal("Removed ${bike.id} with VS body $bodyId") }, true)
        return 1
    }
}
