package kami.essentials.chat

import kami.libs.chat.Theme
import kami.libs.chat.duration
import kami.libs.text.Phrase
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

    fun title(rank: String): MutableComponent = Phrase.or("kami_claims.rank.${rank.lowercase()}", rank.lowercase().replaceFirstChar(Char::uppercase)).component()

    fun stat(p: ServerPlayer, stat: ResourceLocation) = p.stats.getValue(Stats.CUSTOM.get(stat))

    fun playtime(p: ServerPlayer) = duration(stat(p, Stats.PLAY_TIME) / 20L)

    fun player(p: ServerPlayer, hover: Boolean = true): MutableComponent {
        val out = Component.empty()
        citizenship(p.uuid)?.let { out.append(tag(it, hover)).append(" ") }
        return out.append(name(p, hover))
    }

    fun playerGlobal(p: ServerPlayer, hover: Boolean = true): MutableComponent {
        val out = Component.empty()
        citizenship(p.uuid)?.let { out.append(countryTag(it, hover)).append(" ") }
        return out.append(name(p, hover))
    }

    fun playerCountry(p: ServerPlayer, hover: Boolean = true): MutableComponent {
        val out = Component.empty()
        citizenship(p.uuid)?.let { out.append(rankTag(it, hover)).append(" ") }
        return out.append(name(p, hover))
    }

    fun name(p: ServerPlayer, hover: Boolean = true): MutableComponent {
        val name = Component.literal(p.gameProfile.name).withColor(Theme.VALUE)
        if (!hover) return name
        val card = lines(Phrase.literal(p.gameProfile.name) to Theme.VALUE, Phrase.of("kami_essentials.names.playtime", playtime(p)) to Theme.TEXT, Phrase.of("kami_essentials.names.message") to Theme.MUTED)
        return name.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, card)).withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg ${p.gameProfile.name} ")) }
    }

    fun tag(c: Citizenship, hover: Boolean = true): MutableComponent = countryTag(c, hover)

    fun countryTag(c: Citizenship, hover: Boolean = true): MutableComponent {
        val label = c.parent ?: c.name
        val tag = Component.literal("[$label]").withColor(color(c))
        if (!hover) return tag
        val card = lines(
            Phrase.literal(label) to color(c),
            (c.parent?.let { Phrase.of("kami_essentials.names.province", c.name, it) } ?: Phrase.of("kami_essentials.names.independent")) to Theme.TEXT,
            Phrase.or("kami_claims.rank.${c.rank.lowercase()}", c.rank) to Theme.TEXT,
            Phrase.of("kami_essentials.names.country_info") to Theme.MUTED
        )
        return tag.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, card)).withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/claims info $label")) }
    }

    fun rankTag(c: Citizenship, hover: Boolean = true): MutableComponent {
        val label = Phrase.or("kami_claims.rank.${c.rank.lowercase()}", c.rank.lowercase().replaceFirstChar(Char::uppercase))
        val tag = Component.literal("[").append(label.component()).append("]").withColor(color(c))
        if (!hover) return tag
        val card = lines(
            label to color(c),
            (c.parent?.let { Phrase.of("kami_essentials.names.province_rank", c.name) } ?: Phrase.of("kami_essentials.names.country_rank")) to Theme.TEXT,
            Phrase.of("kami_essentials.names.country_info") to Theme.MUTED
        )
        val target = c.parent ?: c.name
        return tag.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, card)).withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/claims info $target")) }
    }

    private fun lines(vararg parts: Pair<Phrase, Int>): Component =
        parts.foldIndexed(Component.empty()) { i, out, (text, color) -> out.append(Component.literal(if (i == 0) "" else "\n").append(text.component()).withColor(color)) }
}
