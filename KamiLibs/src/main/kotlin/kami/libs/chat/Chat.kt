package kami.libs.chat

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap

class Chat private constructor(private val tag: String) {
    fun msg(tone: Tone = Tone.INFO, build: Msg.() -> Unit): Component =
        Msg(tone).text(tag, Theme.ACCENT).text(Theme.SEP, tone.mark).apply(build).out

    fun say(tone: Tone, markup: String) = msg(tone) { markup(markup) }
    fun info(markup: String) = say(Tone.INFO, markup)
    fun ok(markup: String) = say(Tone.OK, markup)
    fun warn(markup: String) = say(Tone.WARN, markup)
    fun bad(markup: String) = say(Tone.BAD, markup)

    companion object {
        private val chats = ConcurrentHashMap<String, Chat>()

        fun of(mod: String): Chat = chats.getOrPut(mod) { Chat(mod.replaceFirstChar(Char::uppercase)) }
        fun row(build: Msg.() -> Unit): Component = Msg().text("  ").apply(build).out
        fun bar(tone: Tone, markup: String): Component = Msg(tone).markup(markup).out
        fun plain(markup: String) = VALUE.replace(markup, "$1")
    }
}

fun ServerPlayer.tell(c: Component) = sendSystemMessage(c)
fun ServerPlayer.bar(c: Component) = displayClientMessage(c, true)
