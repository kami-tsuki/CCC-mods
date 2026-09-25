package kami.libs.config

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

object ConfigCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("kami").requires { it.hasPermission(2) }
                .executes(::help)
                .then(
                    Commands.literal("reload").executes { reload(it, null) }
                        .then(
                            Commands.argument("mod", StringArgumentType.word())
                                .suggests { _, builder -> SharedSuggestionProvider.suggest(Configs.mods, builder) }
                                .executes { reload(it, StringArgumentType.getString(it, "mod")) }
                        )
                )
        )
    }

    private fun help(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        source.sendSuccess({ Component.literal("§6Kami configs §7are in §fconfig/kami/<mod>/") }, false)
        source.sendSuccess({ Component.literal("§7Mods: §f${Configs.mods.joinToString(", ")}") }, false)
        source.sendSuccess({ Component.literal("§7Edit a file, then run §f/kami reload §7or §f/kami reload <mod>") }, false)
        return 1
    }

    private fun reload(context: CommandContext<CommandSourceStack>, mod: String?): Int {
        val results = Configs.reload(mod)
        if (results.isEmpty()) {
            context.source.sendFailure(Component.literal("Unknown mod '$mod'. Try: ${Configs.mods.joinToString(", ")}"))
            return 0
        }
        results.forEach { (name, result) ->
            val line = result.fold({ "§a✔ $name §7${it ?: "reloaded"}" }, { "§c✘ $name §7${Jsonc.reason(it)}" })
            context.source.sendSuccess({ Component.literal(line) }, true)
        }
        return results.count { it.second.isSuccess }
    }
}
