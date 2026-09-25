package kami.libs.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

typealias Ctx = CommandContext<CommandSourceStack>
typealias Node = LiteralArgumentBuilder<CommandSourceStack>

open class CommandFail(message: String) : RuntimeException(message)

fun fail(message: String): Nothing = throw CommandFail(message)

val op: (CommandSourceStack) -> Boolean = { it.hasPermission(2) }

fun lit(name: String): Node = Commands.literal(name)

fun <T> arg(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> = Commands.argument(name, type)

fun word(name: String, options: () -> Collection<String>) =
    arg(name, StringArgumentType.word()).suggests { _, b -> SharedSuggestionProvider.suggest(options(), b) }

fun <T : ArgumentBuilder<CommandSourceStack, T>> T.does(action: (Ctx) -> Unit): T = executes {
    try {
        action(it)
        1
    } catch (e: CommandFail) {
        it.source.sendFailure(Component.literal(e.message ?: "Failed."))
        0
    }
}

fun Ctx.me(): ServerPlayer = source.player ?: fail("Only players can use this.")
fun Ctx.text(name: String): String = StringArgumentType.getString(this, name)
fun Ctx.int(name: String): Int = IntegerArgumentType.getInteger(this, name)
fun Ctx.say(message: String, broadcast: Boolean = false) = source.sendSuccess({ Component.literal(message) }, broadcast)
