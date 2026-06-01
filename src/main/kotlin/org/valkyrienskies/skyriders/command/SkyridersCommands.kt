package org.valkyrienskies.skyriders.command

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import org.joml.Vector3d
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
                            argument("bike", ResourceLocationArgument.id())
                                .then(
                                    argument("pos", Vec3Argument.vec3())
                                        .executes { ctx ->
                                            summonBike(
                                                ctx.source,
                                                ResourceLocationArgument.getId(ctx, "bike"),
                                                Vec3Argument.getVec3(ctx, "pos")
                                            )
                                        }
                                )
                        )
                )
        )
    }

    private fun summonBike(source: CommandSourceStack, bikeId: ResourceLocation, pos: Vec3): Int {
        val level = source.level as? ServerLevel
            ?: run {
                source.sendFailure(Component.literal("Bikes can only be summoned on the server."))
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
}
