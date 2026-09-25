package kami.libs.chat

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent

class Msg(val tone: Tone = Tone.INFO) {
    val out: MutableComponent = Component.empty()

    fun text(s: String, color: Int = tone.body) = apply { out.append(Component.literal(s).withColor(color)) }
    fun muted(s: String) = text(s, Theme.MUTED)
    fun value(v: Any) = text(v.toString(), Theme.VALUE)
    fun good(v: Any) = text(v.toString(), Theme.OK)
    fun bad(v: Any) = text(v.toString(), Theme.BAD)
    fun add(c: Component) = apply { out.append(c) }

    fun markup(s: String) = apply {
        var i = 0
        while (i < s.length) {
            val open = s.indexOf('{', i)
            val close = if (open < 0) -1 else s.indexOf('}', open)
            if (open < 0 || close < 0) {
                text(s.substring(i))
                break
            }
            if (open > i) text(s.substring(i, open))
            value(s.substring(open + 1, close))
            i = close + 1
        }
    }

    fun pos(x: Int, y: Int, z: Int, dim: String? = null) = link(
        "$x, $y, $z",
        ClickEvent(ClickEvent.Action.RUN_COMMAND, (dim?.let { "/execute in $it run " } ?: "/") + "tp @s $x $y $z"),
        "Click to teleport"
    )

    fun button(label: String, command: String, hover: String = command) = run("[$label]", command, hover)

    fun run(label: String, command: String, hover: String = command) =
        link(label, ClickEvent(ClickEvent.Action.RUN_COMMAND, command), hover)

    fun suggest(label: String, command: String, hover: String = "Click to fill in") =
        link(label, ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command), hover)

    fun copy(label: String, value: String) =
        link(label, ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value), "Click to copy")

    private fun link(label: String, click: ClickEvent, hover: String) = apply {
        out.append(Component.literal(label).withStyle { it.withColor(Theme.LINK).withClickEvent(click).withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover).withColor(Theme.TEXT))) })
    }
}
