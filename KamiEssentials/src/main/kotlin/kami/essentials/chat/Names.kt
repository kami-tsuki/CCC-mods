package kami.essentials.chat

import kami.libs.chat.Theme
import kami.libs.chat.duration
import kami.libs.claims.Citizenship
import kami.libs.claims.ClaimsApi
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import java.util.UUID

object Names {
    fun citizenship(id: UUID): Citizenship? = if (ClaimsApi.present) ClaimsApi.citizenship(id) else null

    fun color(c: Citizenship) = c.color.takeIf { it != 0 } ?: Theme.ACCENT

    fun title(rank: String) = rank.lowercase().replaceFirstChar(Char::uppercase)

    fun stat(p: ServerPlayer, stat: ResourceLocation) = p.stats.getValue(Stats.CUSTOM.get(stat))

    fun playtime(p: ServerPlayer) = duration(stat(p, Stats.PLAY_TIME) / 20L)

    fun player(p: ServerPlayer, hover: Boolean = true): MutableComponent {
        val out = Component.empty()
        citizenship(p.uuid)?.let { out.append(tag(it, hover)).append(" ") }
        return out.append(name(p, hover))
    }

    fun name(p: ServerPlayer, hover: Boolean = true): MutableComponent {
        val name = Component.literal(p.gameProfile.name).withColor(Theme.VALUE)
        if (!hover) return name
        val card = lines(p.gameProfile.name to Theme.VALUE, "Playtime ${playtime(p)}" to Theme.TEXT, "Click to message" to Theme.MUTED)
        return name.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, card)).withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg ${p.gameProfile.name} ")) }
    }

    fun tag(c: Citizenship, hover: Boolean = true): MutableComponent {
        val tag = Component.literal("[${c.name}]").withColor(color(c))
        if (!hover) return tag
        val card = lines(
            c.name to color(c),
            (c.parent?.let { "Province of $it" } ?: "Independent country") to Theme.TEXT,
            title(c.rank) to Theme.TEXT,
            "Click for country info" to Theme.MUTED
        )
        return tag.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, card)).withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/claims info ${c.name}")) }
    }

    private fun lines(vararg parts: Pair<String, Int>): Component =
        parts.foldIndexed(Component.empty()) { i, out, (text, color) -> out.append(Component.literal(if (i == 0) text else "\n$text").withColor(color)) }
}
