package kami.libs.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import kami.libs.chat.Chat
import kami.libs.chat.Msg
import kami.libs.chat.Tone
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
        it.source.sendFailure(it.chat.bad(e.message ?: "That did not work."))
        0
    }
}

val Ctx.chat: Chat get() = nodes.map { it.node.name }.let { Chat.of(if (it.size > 1 && it[0] == "kami") it[1] else KamiCommands.owner(it.firstOrNull() ?: "kami")) }

fun Ctx.me(): ServerPlayer = source.player ?: fail("Only players can use this.")
fun Ctx.text(name: String): String = StringArgumentType.getString(this, name)
fun Ctx.int(name: String): Int = IntegerArgumentType.getInteger(this, name)

fun Ctx.reply(c: Component, broadcast: Boolean = false) = source.sendSuccess({ c }, broadcast)
fun Ctx.msg(tone: Tone = Tone.INFO, build: Msg.() -> Unit) = reply(chat.msg(tone, build))
fun Ctx.row(build: Msg.() -> Unit) = reply(Chat.row(build))
fun Ctx.info(markup: String) = reply(chat.info(markup))
fun Ctx.ok(markup: String, broadcast: Boolean = false) = reply(chat.ok(markup), broadcast)
fun Ctx.warn(markup: String) = reply(chat.warn(markup))
