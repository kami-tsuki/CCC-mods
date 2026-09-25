package kami.libs.chat

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap

class Chat(val tag: String) {
    fun msg(tone: Tone = Tone.INFO, build: Msg.() -> Unit): Component = Msg(tone).apply {
        text(tag, Theme.ACCENT)
        text(Theme.SEP, tone.mark)
        build()
    }.out

    fun info(markup: String) = msg(Tone.INFO) { markup(markup) }
    fun ok(markup: String) = msg(Tone.OK) { markup(markup) }
    fun warn(markup: String) = msg(Tone.WARN) { markup(markup) }
    fun bad(markup: String) = msg(Tone.BAD) { markup(markup) }
    fun of(tone: Tone, markup: String) = msg(tone) { markup(markup) }

    fun head(title: String, build: Msg.() -> Unit = {}) = msg { value(title); build() }

    companion object {
        private val chats = ConcurrentHashMap<String, Chat>()

        fun of(mod: String): Chat = chats.getOrPut(mod) { Chat(mod.replaceFirstChar(Char::uppercase)) }

        fun row(build: Msg.() -> Unit): Component = Msg().apply { text("  ") }.apply(build).out

        fun bar(tone: Tone, markup: String): Component = Msg(tone).markup(markup).out

        fun plain(markup: String) = markup.replace("{", "").replace("}", "")
    }
}

fun ServerPlayer.tell(c: Component) = sendSystemMessage(c)
fun ServerPlayer.bar(c: Component) = displayClientMessage(c, true)
