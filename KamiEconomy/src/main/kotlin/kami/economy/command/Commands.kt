package kami.economy.command

import com.mojang.brigadier.arguments.LongArgumentType
import kami.economy.Market
import kami.economy.net.Net
import kami.libs.command.KamiCommands
import kami.libs.command.arg
import kami.libs.command.does
import kami.libs.command.lit
import kami.libs.command.me
import kami.libs.command.op
import kami.libs.command.say

object EconomyCommands {
    fun register() = KamiCommands.module("economy", "Server market and auction house") {
        does { Net.send(it.me(), open = true) }
        then(lit("market").does { Net.send(it.me(), open = true) })
        then(
            lit("admin").requires(op).then(
                lit("resolve").then(
                    arg("id", LongArgumentType.longArg()).does { ctx ->
                        val id = LongArgumentType.getLong(ctx, "id")
                        ctx.say(if (Market.data.frozen.remove(id)) "Cleared frozen intent $id." else "No frozen intent $id.")
                    }
                )
            )
        )
    }
}
