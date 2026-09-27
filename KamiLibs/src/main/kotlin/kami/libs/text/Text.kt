package kami.libs.text

import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

object Text {
    fun msg(key: String, vararg args: Any): MutableComponent =
        Component.translatableWithFallback(key, Language.getInstance().getOrDefault(key), *args)
}
