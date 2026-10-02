package kami.libs.text

import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

object Text {
    fun lineCard(vararg lines: Pair<Phrase, Int>): Component =
        lines.foldIndexed(Component.empty()) { i, out, (text, color) -> out.append(Component.literal(if (i == 0) "" else "\n").append(text.component()).withColor(color)) }

    fun msg(key: String, vararg args: Any): MutableComponent =
        Component.translatableWithFallback(key, Language.getInstance().getOrDefault(key), *args)
}
