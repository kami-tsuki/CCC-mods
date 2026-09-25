package kami.libs.chat

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent

internal val VALUE = Regex("""\{([^{}]*)}""")

class Msg(val tone: Tone = Tone.INFO) {
    val out: MutableComponent = Component.empty()

    fun text(s: String, color: Int = tone.body) = apply { out.append(Component.literal(s).withColor(color)) }
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
        link("$x, $y, $z", ClickEvent.Action.RUN_COMMAND, "/execute in $dim run tp @s $x $y $z", "Click to teleport")

    fun run(label: String, command: String, hover: String = command) = link(label, ClickEvent.Action.RUN_COMMAND, command, hover)
    fun button(label: String, command: String, hover: String = command) = run("[$label]", command, hover)
    fun suggest(label: String, command: String, hover: String) = link(label, ClickEvent.Action.SUGGEST_COMMAND, command, hover)

    private fun link(label: String, action: ClickEvent.Action, command: String, hover: String) = apply {
        out.append(Component.literal(label).withStyle {
            it.withColor(Theme.LINK)
                .withClickEvent(ClickEvent(action, command))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover).withColor(Theme.TEXT)))
        })
    }
}
