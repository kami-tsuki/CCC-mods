package kami.libs.ui.text

import kami.libs.economy.Statement
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.resources.ResourceLocation

fun noteLabel(raw: String): String {
    if (!raw.startsWith(Statement.NOTE)) return raw
    val parts = raw.removePrefix(Statement.NOTE).split('|')
    return tr(parts.first(), *parts.drop(1).map(::argLabel).toTypedArray())
}

private fun argLabel(arg: String): String {
    if (Language.getInstance().has(arg)) return tr(arg)
    if (':' !in arg) return arg
    val id = ResourceLocation.tryParse(arg) ?: return arg
    return BuiltInRegistries.ITEM.getOptional(id).map { it.description.string }.orElse(arg)
}
