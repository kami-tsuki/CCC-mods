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
import kami.libs.text.Phrase
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

typealias Ctx = CommandContext<CommandSourceStack>
typealias Node = LiteralArgumentBuilder<CommandSourceStack>

open class CommandFail(val render: () -> Component) : RuntimeException() {
    constructor(markup: String) : this({ Msg(Tone.BAD).markup(markup).out })
}

fun fail(markup: String): Nothing = throw CommandFail(markup)
fun fail(text: Component): Nothing = throw CommandFail { text }
fun fail(phrase: Phrase): Nothing = throw CommandFail(phrase::component)

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
        it.source.sendFailure(it.chat.bad(e.render()))
        0
    }
}

val Ctx.chat: Chat get() = nodes.map { it.node.name }.let { Chat.of(if (it.size > 1 && it[0] == "kami") it[1] else KamiCommands.owner(it.firstOrNull() ?: "kami")) }

fun Ctx.me(): ServerPlayer = source.player ?: fail(Phrase.of("kami_libs.command.players_only"))
fun Ctx.text(name: String): String = StringArgumentType.getString(this, name)
fun Ctx.int(name: String): Int = IntegerArgumentType.getInteger(this, name)

fun Ctx.reply(c: Component, broadcast: Boolean = false) = source.sendSuccess({ c }, broadcast)
fun Ctx.msg(tone: Tone = Tone.INFO, build: Msg.() -> Unit) = reply(chat.msg(tone, build))
fun Ctx.row(build: Msg.() -> Unit) = reply(Chat.row(build))
fun Ctx.info(markup: String) = reply(chat.info(markup))
fun Ctx.ok(markup: String, broadcast: Boolean = false) = reply(chat.ok(markup), broadcast)
fun Ctx.warn(markup: String) = reply(chat.warn(markup))
fun Ctx.info(text: Component) = reply(chat.info(text))
fun Ctx.ok(text: Component, broadcast: Boolean = false) = reply(chat.ok(text), broadcast)
fun Ctx.warn(text: Component) = reply(chat.warn(text))
fun Ctx.info(phrase: Phrase) = info(phrase.component())
fun Ctx.ok(phrase: Phrase, broadcast: Boolean = false) = ok(phrase.component(), broadcast)
fun Ctx.warn(phrase: Phrase) = warn(phrase.component())
