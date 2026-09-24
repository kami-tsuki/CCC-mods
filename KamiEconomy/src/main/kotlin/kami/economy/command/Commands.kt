package kami.economy.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.LongArgumentType
import kami.economy.Market
import kami.economy.net.Net
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object EconomyCommands {
    fun register(d: CommandDispatcher<CommandSourceStack>) {
        d.register(
            Commands.literal("market").executes { ctx ->
                val p = ctx.source.player ?: return@executes 0
                Net.send(p, open = true)
                1
            }
        )
        d.register(
            Commands.literal("economy").then(
                Commands.literal("admin").requires { it.hasPermission(2) }.then(
                    Commands.literal("resolve").then(
                        Commands.argument("id", LongArgumentType.longArg()).executes { ctx ->
                            val id = LongArgumentType.getLong(ctx, "id")
                            val removed = Market.data.frozen.remove(id)
                            ctx.source.sendSuccess({ Component.literal(if (removed) "Cleared frozen intent $id." else "No frozen intent $id.") }, false)
                            1
                        }
                    )
                )
            )
        )
    }
}
