package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Palette
import net.minecraft.client.Minecraft
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.effect.MobEffect

const val EFFECT_ICON = 18

private val holders = HashMap<String, Holder<MobEffect>?>()
private val names = HashMap<String, String>()
private val numerals = arrayOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

fun roman(n: Int) = numerals.getOrNull(n) ?: n.toString()

private fun holder(id: String) = holders.getOrPut(id) {
    ResourceLocation.tryParse(id)?.let { BuiltInRegistries.MOB_EFFECT.getHolder(it).orElse(null) }
}

fun effectName(id: String): String = names.getOrPut(id) {
    holder(id)?.value()?.displayName?.string ?: id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun Ui.effectIcon(r: Rect, id: String, dim: Float = 0f) {
    val effect = holder(id) ?: return
    val x = r.x + (r.w - EFFECT_ICON) / 2
    val y = r.y + (r.h - EFFECT_ICON) / 2
    g.blit(x, y, 0, EFFECT_ICON, EFFECT_ICON, Minecraft.getInstance().mobEffectTextures.get(effect))
    if (dim > 0f) g.fill(x, y, x + EFFECT_ICON, y + EFFECT_ICON, Palette.alpha(Palette.surface, (dim * 0xFF).toInt()))
}
