package kami.libs.chat

import kami.libs.text.Phrase
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

    fun say(tone: Tone, text: Component) = msg(tone) { add(text) }
    fun info(text: Component) = say(Tone.INFO, text)
    fun ok(text: Component) = say(Tone.OK, text)
    fun warn(text: Component) = say(Tone.WARN, text)
    fun bad(text: Component) = say(Tone.BAD, text)

    fun say(tone: Tone, phrase: Phrase) = say(tone, phrase.component())
    fun info(phrase: Phrase) = say(Tone.INFO, phrase)
    fun ok(phrase: Phrase) = say(Tone.OK, phrase)
    fun warn(phrase: Phrase) = say(Tone.WARN, phrase)
    fun bad(phrase: Phrase) = say(Tone.BAD, phrase)

    companion object {
        private val chats = ConcurrentHashMap<String, Chat>()

        fun of(mod: String): Chat = chats.getOrPut(mod) { Chat(mod.replaceFirstChar(Char::uppercase)) }
        fun row(build: Msg.() -> Unit): Component = Msg().text("  ").apply(build).out
        fun bar(tone: Tone, markup: String): Component = Msg(tone).markup(markup).out
        fun bar(tone: Tone, text: Component): Component = Msg(tone).add(text).out
        fun bar(tone: Tone, phrase: Phrase): Component = bar(tone, phrase.component())
        fun plain(markup: String) = VALUE.replace(markup, "$1")
    }
}

fun ServerPlayer.tell(c: Component) = sendSystemMessage(c)
fun ServerPlayer.bar(c: Component) = displayClientMessage(c, true)
