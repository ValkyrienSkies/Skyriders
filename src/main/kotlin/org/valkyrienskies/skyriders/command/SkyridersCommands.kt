package org.valkyrienskies.skyriders.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.BikeInteractionHandler
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.racing.RaceManager
import org.valkyrienskies.skyriders.network.SkyridersNetworkStats
import org.joml.Vector3d

object SkyridersCommands {
    fun register(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(commandRoot("skyrider"))
        dispatcher.register(commandRoot("skyriders"))
    }

    private fun commandRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> {
        return literal(name)
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
                                ).executes { ctx ->
                                    summonBike(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "bike"),
                                        ctx.source.position
                                    )
                                }
                        )
                )
                .then(
                    literal("input")
                        .then(
                            argument("bodyId", LongArgumentType.longArg())
                                .suggests { ctx, builder -> suggestLoadedVehicleIds(ctx.source, builder) }
                                .then(inputSetter("steer", -1.0, 1.0) { input, value -> input.copy(steer = value, riderPresent = true) })
                                .then(inputSetter("throttle", -1.0, 1.0) { input, value -> input.copy(throttle = value, riderPresent = true) })
                                .then(inputSetter("brake", 0.0, 1.0) { input, value -> input.copy(brake = value, riderPresent = true) })
                                .then(inputSetter("jump", 0.0, 1.0) { input, value -> input.copy(jump = value, riderPresent = true) })
                                .then(inputSetter("pitch", -1.0, 1.0) { input, value -> input.copy(pitch = value, riderPresent = true) })
                                .then(
                                    literal("reset")
                                        .executes { ctx ->
                                            setInput(
                                                ctx.source,
                                                LongArgumentType.getLong(ctx, "bodyId"),
                                                VehicleInput.EMPTY
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
                                .suggests { ctx, builder -> suggestLoadedVehicleIds(ctx.source, builder) }
                                .executes { ctx ->
                                    removeBike(ctx.source, LongArgumentType.getLong(ctx, "bodyId"))
                                }
                        )
                )
                .then(
                    literal("ride")
                        .then(
                            argument("bodyId", LongArgumentType.longArg())
                                .suggests { ctx, builder -> suggestLoadedVehicleIds(ctx.source, builder) }
                                .executes { ctx ->
                                    rideBike(ctx.source, LongArgumentType.getLong(ctx, "bodyId"))
                                }
                        )
                )
                .then(
                    literal("teleport")
                        .then(
                            argument("bodyId", LongArgumentType.longArg())
                                .suggests { ctx, builder -> suggestLoadedVehicleIds(ctx.source, builder) }
                                .then(
                                    argument("pos", Vec3Argument.vec3())
                                        .executes { ctx ->
                                            teleportVehicle(
                                                ctx.source,
                                                LongArgumentType.getLong(ctx, "bodyId"),
                                                Vec3Argument.getVec3(ctx, "pos")
                                            )
                                        }
                                )
                        )
                )
                .then(
                    literal("netstats")
                        .executes { ctx -> showNetworkStats(ctx.source) }
                )
                .then(
                    literal("race")
                        .then(
                            literal("locatenext")
                                .executes { ctx -> locateNextRaceCheckpoint(ctx.source) }
                        )
                        .then(
                            literal("tpnext")
                                .executes { ctx -> teleportToNextRaceCheckpoint(ctx.source, null) }
                                .then(
                                    argument("choice", IntegerArgumentType.integer(1))
                                        .executes { ctx ->
                                            teleportToNextRaceCheckpoint(
                                                ctx.source,
                                                IntegerArgumentType.getInteger(ctx, "choice")
                                            )
                                        }
                                )
                        )
                        .then(
                            literal("end")
                                .then(
                                    argument("color", StringArgumentType.word())
                                        .suggests { ctx, builder ->
                                            SharedSuggestionProvider.suggest(
                                                RaceManager.activeRaceColorSuggestions(ctx.source.level),
                                                builder
                                            )
                                        }
                                        .executes { ctx ->
                                            endRace(
                                                ctx.source,
                                                StringArgumentType.getString(ctx, "color")
                                            )
                                        }
                                )
                        )
                )
    }

    private fun bikeIdSuggestions(): Iterable<String> {
        return VehicleManager.registeredVehicleIds.flatMap { id ->
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

    private fun suggestLoadedVehicleIds(
        source: CommandSourceStack,
        builder: com.mojang.brigadier.suggestion.SuggestionsBuilder
    ): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        return SharedSuggestionProvider.suggest(loadedVehicleIdSuggestions(source), builder)
    }

    private fun loadedVehicleIdSuggestions(source: CommandSourceStack): Iterable<String> {
        return VehicleManager.getVehicles(source.level.dimensionId)
            .map { it.bodyId.toString() }
            .sorted()
    }

    private fun parseRaceColor(input: String): Int? {
        val normalized = input.removePrefix("#")
        if (!Regex("[0-9a-fA-F]{6}").matches(normalized)) return null
        return normalized.toIntOrNull(16)?.and(0xFFFFFF)
    }

    private fun inputSetter(
        name: String,
        min: Double,
        max: Double,
        update: (VehicleInput, Double) -> VehicleInput
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

        val vehicle = try {
            VehicleManager.createVehicle(bikeId, level, Vector3d(pos.x, pos.y, pos.z))
        } catch (ex: IllegalArgumentException) {
            source.sendFailure(Component.literal(ex.message ?: "Unknown bike id: $bikeId"))
            return 0
        } catch (ex: IllegalStateException) {
            source.sendFailure(Component.literal(ex.message ?: "VS body world is not ready."))
            return 0
        }

        source.sendSuccess(
            { Component.literal("Summoned ${vehicle.vehicleDefinition.displayName} (${vehicle.id}) as VS body ${vehicle.bodyId}") },
            true
        )
        return 1
    }

    private fun updateInput(
        source: CommandSourceStack,
        bodyId: BodyId,
        label: String,
        updater: (VehicleInput) -> VehicleInput
    ): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bike input can only be set on the server."))
                return 0
            }

        val input = VehicleManager.updateInput(level.dimensionId, bodyId, updater)
            ?: run {
                source.sendFailure(Component.literal("No vehicle with VS body id $bodyId in this dimension."))
                return 0
            }

        source.sendSuccess(
            { Component.literal("Set $label for vehicle $bodyId: steer=${input.steer}, throttle=${input.throttle}, brake=${input.brake}, jump=${input.jump}, pitch=${input.pitch}, handbrake=${input.handbrake}, rider=${input.riderPresent}") },
            false
        )
        return 1
    }

    private fun setInput(source: CommandSourceStack, bodyId: BodyId, input: VehicleInput): Int {
        return updateInput(source, bodyId, "input") { input }
    }

    private fun listBikes(source: CommandSourceStack): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bikes can only be listed on the server."))
                return 0
            }

        val vehicles = VehicleManager.getVehicles(level.dimensionId)
        if (vehicles.isEmpty()) {
            source.sendSuccess({ Component.literal("No vehicles in this dimension.") }, false)
            return 1
        }

        val message = vehicles.joinToString(separator = "\n") { vehicle ->
            val input = VehicleManager.getInput(level.dimensionId, vehicle.bodyId)
            "${vehicle.bodyId}: ${vehicle.vehicleDefinition.displayName} (${vehicle.id}) engine=${vehicle.vehicleState.engineOn} input[steer=${input.steer}, throttle=${input.throttle}, brake=${input.brake}, jump=${input.jump}, pitch=${input.pitch}, handbrake=${input.handbrake}, rider=${input.riderPresent}]"
        }
        source.sendSuccess({ Component.literal(message) }, false)
        return vehicles.size
    }

    private fun removeBike(source: CommandSourceStack, bodyId: BodyId): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bikes can only be removed on the server."))
                return 0
            }

        val vehicle = try {
            VehicleManager.removeVehicle(level, bodyId)
        } catch (ex: IllegalStateException) {
            source.sendFailure(Component.literal(ex.message ?: "VS body world is not ready."))
            return 0
        }

        if (vehicle == null) {
            source.sendFailure(Component.literal("No vehicle with VS body id $bodyId in this dimension."))
            return 0
        }

        source.sendSuccess({ Component.literal("Removed ${vehicle.vehicleDefinition.displayName} (${vehicle.id}) with VS body $bodyId") }, true)
        return 1
    }

    private fun rideBike(source: CommandSourceStack, bodyId: BodyId): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bikes can only be ridden on the server."))
                return 0
            }
        val player = source.player as? ServerPlayer
            ?: run {
                source.sendFailure(Component.literal("Only players can ride bikes."))
                return 0
            }
        val vehicle = VehicleManager.getVehicle(level.dimensionId, bodyId)
            ?: run {
                source.sendFailure(Component.literal("No vehicle with VS body id $bodyId in this dimension."))
                return 0
            }

        if (!BikeInteractionHandler.mountVehicle(player, vehicle, notifyPlayer = false)) {
            source.sendFailure(Component.literal("Could not mount ${vehicle.vehicleDefinition.displayName} (${vehicle.id})."))
            return 0
        }
        source.sendSuccess({ Component.literal("Mounted ${vehicle.vehicleDefinition.displayName} (${vehicle.id}) with VS body $bodyId") }, false)
        return 1
    }

    private fun teleportVehicle(source: CommandSourceStack, bodyId: BodyId, pos: Vec3): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Vehicles can only be teleported on the server."))
                return 0
            }
        val vehicle = try {
            VehicleManager.teleportVehicle(level, bodyId, Vector3d(pos.x, pos.y, pos.z))
        } catch (ex: IllegalStateException) {
            source.sendFailure(Component.literal(ex.message ?: "VS body world is not ready."))
            return 0
        }
        if (vehicle == null) {
            source.sendFailure(Component.literal("No vehicle with VS body id $bodyId in this dimension."))
            return 0
        }
        source.sendSuccess(
            {
                Component.literal(
                    "Teleported ${vehicle.vehicleDefinition.displayName} (${vehicle.id}) body $bodyId to ${formatCoord(pos.x)}, ${formatCoord(pos.y)}, ${formatCoord(pos.z)}."
                )
            },
            true
        )
        return 1
    }

    private fun showNetworkStats(source: CommandSourceStack): Int {
        val snapshot = SkyridersNetworkStats.snapshot()
        if (snapshot.rows.isEmpty()) {
            source.sendSuccess({ Component.literal("No Skyriders packet stats recorded yet.") }, false)
            return 1
        }

        val message = Component.literal(
            "Skyriders network stats, ${formatNumber(snapshot.elapsedSeconds)}s: " +
                "${snapshot.totalPackets} packets, ${formatBytes(snapshot.totalEstimatedBytes)} estimated"
        )
        snapshot.rows.take(12).forEach { row ->
            message.append(
                Component.literal(
                    "\n${row.packetType}: " +
                        "${row.packets} packets (${formatNumber(row.packetsPerSecond(snapshot.elapsedSeconds))}/s), " +
                        "${formatBytes(row.estimatedBytes)} (${formatBytesPerSecond(row.bytesPerSecond(snapshot.elapsedSeconds))}), " +
                        "items=${row.items}"
                )
            )
        }
        if (snapshot.rows.size > 12) {
            message.append(Component.literal("\n... ${snapshot.rows.size - 12} more packet types"))
        }
        source.sendSuccess({ message }, false)
        return snapshot.rows.size
    }

    private fun endRace(source: CommandSourceStack, colorInput: String): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Races can only be ended on the server."))
                return 0
            }
        val color = parseRaceColor(colorInput)
            ?: run {
                source.sendFailure(Component.literal("Invalid race color: $colorInput. Expected six hex digits like FAFAFA."))
                return 0
            }

        if (!RaceManager.endRace(level, color)) {
            source.sendFailure(Component.literal("No active race with color %06X in this dimension.".format(color)))
            return 0
        }
        source.sendSuccess({ Component.literal("Ended race %06X.".format(color)) }, true)
        return 1
    }

    private fun locateNextRaceCheckpoint(source: CommandSourceStack): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Race checkpoints can only be located on the server."))
                return 0
            }
        val player = source.player as? ServerPlayer
            ?: run {
                source.sendFailure(Component.literal("Only players can locate their next race checkpoint."))
                return 0
            }
        val options = RaceManager.nextMarkerOptions(level, player)
            ?: run {
                source.sendFailure(Component.literal("You are not driving in an active race, or the next checkpoint has no valid linked line."))
                return 0
            }

        val label = nextRaceMarkerLabel(options.markerType, options.nextCheckpointIndex)
        val message = Component.literal(
            "Next race target for %06X, lap ${options.currentLap}/${options.totalLaps}: $label (${options.options.size} line${if (options.options.size == 1) "" else "s"})"
                .format(options.colorId)
        )
        options.options.forEachIndexed { index, option ->
            val choice = index + 1
            val center = option.center
            val endpoint = option.endpointPos?.let { " -> ${it.x}, ${it.y}, ${it.z}" } ?: ""
            val command = "/skyriders race tpnext $choice"
            val tp = Component.literal(" [TP]")
                .withStyle { style ->
                    style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))
                }
            message.append(
                Component.literal(
                    "\n#$choice center=${formatCoord(center.x)}, ${formatCoord(center.y)}, ${formatCoord(center.z)} marker=${option.markerPos.x}, ${option.markerPos.y}, ${option.markerPos.z}$endpoint"
                )
            ).append(tp)
        }

        source.sendSuccess({ message }, false)
        return options.options.size
    }

    private fun teleportToNextRaceCheckpoint(source: CommandSourceStack, choice: Int?): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Race checkpoints can only be teleported to on the server."))
                return 0
            }
        val player = source.player as? ServerPlayer
            ?: run {
                source.sendFailure(Component.literal("Only players can teleport to their next race checkpoint."))
                return 0
            }
        val options = RaceManager.nextMarkerOptions(level, player)
            ?: run {
                source.sendFailure(Component.literal("You are not driving in an active race, or the next checkpoint has no valid linked line."))
                return 0
            }
        if (choice == null && options.options.size != 1) {
            source.sendFailure(Component.literal("There are ${options.options.size} next checkpoint lines. Use /skyriders race locatenext, then /skyriders race tpnext <choice>."))
            return 0
        }
        val index = (choice ?: 1) - 1
        val option = options.options.getOrNull(index)
            ?: run {
                source.sendFailure(Component.literal("Invalid checkpoint choice ${choice ?: 1}; valid range is 1-${options.options.size}."))
                return 0
            }
        val center = option.center
        player.teleportTo(level, center.x, center.y + 1.0, center.z, player.yRot, player.xRot)
        source.sendSuccess(
            {
                Component.literal(
                    "Teleported to ${nextRaceMarkerLabel(options.markerType, options.nextCheckpointIndex)} #${index + 1} at ${formatCoord(center.x)}, ${formatCoord(center.y + 1.0)}, ${formatCoord(center.z)}."
                )
            },
            false
        )
        return 1
    }

    private fun nextRaceMarkerLabel(markerType: String, checkpointIndex: Int): String {
        return if (markerType == "start_finish") {
            "start/finish"
        } else {
            "checkpoint index $checkpointIndex"
        }
    }

    private fun formatNumber(value: Double): String = "%.1f".format(value)

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> "${formatNumber(bytes / (1024.0 * 1024.0))} MiB"
            bytes >= 1024L -> "${formatNumber(bytes / 1024.0)} KiB"
            else -> "$bytes B"
        }
    }

    private fun formatBytesPerSecond(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024.0 * 1024.0 -> "${formatNumber(bytesPerSecond / (1024.0 * 1024.0))} MiB/s"
            bytesPerSecond >= 1024.0 -> "${formatNumber(bytesPerSecond / 1024.0)} KiB/s"
            else -> "${formatNumber(bytesPerSecond)} B/s"
        }
    }

    private fun formatCoord(value: Double): String = "%.2f".format(value)
}
