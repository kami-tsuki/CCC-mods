package kami.libs.chat

import kami.libs.text.Phrase
import kami.libs.text.Text
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent

internal val VALUE = Regex("""\{([^{}]*)}""")

class Msg(val tone: Tone = Tone.INFO) {
    val out: MutableComponent = Component.empty()

    fun text(s: String, color: Int = tone.body) = apply { out.append(Component.literal(s).withColor(color)) }
    fun add(c: Component, color: Int = tone.body) = apply { out.append(c.copy().withColor(color)) }
    fun add(p: Phrase, color: Int = tone.body) = add(p.component(), color)
    fun muted(s: String) = text(s, Theme.MUTED)
    fun value(v: Any) = text(v.toString(), Theme.VALUE)
    fun good(v: Any) = text(v.toString(), Theme.OK)
    fun bad(v: Any) = text(v.toString(), Theme.BAD)

    fun markup(s: String) = apply {
        var from = 0
        VALUE.findAll(s).forEach {
            text(s.substring(from, it.range.first))
            value(it.groupValues[1])
            from = it.range.last + 1
        }
        text(s.substring(from))
    }

    fun pos(x: Int, y: Int, z: Int, dim: String) =
        link(Component.literal("$x, $y, $z"), ClickEvent.Action.RUN_COMMAND, "/execute in $dim run tp @s $x $y $z", Text.msg("kami_libs.chat.teleport.tooltip"))

    fun run(label: String, command: String, hover: String = command) = link(Component.literal(label), ClickEvent.Action.RUN_COMMAND, command, Component.literal(hover))
    fun run(label: Component, command: String, hover: Component) = link(label, ClickEvent.Action.RUN_COMMAND, command, hover)
    fun button(label: String, command: String, hover: String = command) = run("[$label]", command, hover)
    fun button(label: Component, command: String, hover: Component) = run(Component.literal("[").append(label).append("]"), command, hover)
    fun button(label: Phrase, command: String, hover: Phrase) = button(label.component(), command, hover.component())
    fun suggest(label: String, command: String, hover: String) = link(Component.literal(label), ClickEvent.Action.SUGGEST_COMMAND, command, Component.literal(hover))
    fun suggest(label: String, command: String, hover: Component) = link(Component.literal(label), ClickEvent.Action.SUGGEST_COMMAND, command, hover)

    private fun link(label: Component, action: ClickEvent.Action, command: String, hover: Component) = apply {
        out.append(label.copy().withStyle {
            it.withColor(Theme.LINK)
                .withClickEvent(ClickEvent(action, command))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, hover.copy().withColor(Theme.TEXT)))
        })
    }
}
