package kami.economy.quest

import kami.economy.KamiEconomy
import kami.economy.LOG
import kami.economy.economy.DiscordMarket
import kami.economy.economy.Notify
import kami.economy.economy.realName
import kami.economy.now
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.tell
import kami.libs.claims.ClaimsApi
import kami.libs.discord.Card
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordFormat
import kami.libs.discord.DiscordText
import kami.libs.discord.Embed
import kami.libs.text.Phrase
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

internal object QuestAnnounce {
    private const val ID = KamiEconomy.ID
    private const val LOCALE = DiscordText.FALLBACK
    private val chat = Chat.of("market")

    private fun text(key: String, vararg args: Any) = DiscordText.get(ID, LOCALE, key, *args)

    private fun rarity(r: Int) = text("kami_economy.quest.rarity.$r")

    private fun name(item: String): String {
        val found = ResourceLocation.tryParse(item)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
        return found?.let { realName(it.description.string, it.descriptionId) } ?: DiscordMarket.pretty(item)
    }

    private fun boost(b: QuestBoost) = text("kami_economy.quest.boost", b.pct, text("kami_economy.quest.kind.${b.kind}"), b.hours)

    private fun boosts(q: Quest) = q.boosts.joinToString(", ", transform = ::boost).ifEmpty { text("kami_economy.discord.quest_none") }

    private fun safely(what: String, block: () -> Unit) = runCatching(block).onFailure { LOG.warn("Quest {} announcement failed", what, it) }

    fun spawned(q: Quest) {
        val server = Notify.server
        if (server == null && !DiscordApi.ready) return
        safely("spawn") {
            val color = Rarity.colors[q.rarity]
            server?.playerList?.players?.forEach { p ->
                p.tell(chat.say(Tone.INFO, Phrase.of("kami_economy.quest.appeared", Phrase.of("kami_economy.quest.rarity.${q.rarity}")).component().withColor(color)))
            }
            DiscordApi.event(Embed(text("kami_economy.discord.quest_event", rarity(q.rarity)), color))
            if (!DiscordApi.marketOn) return@safely
            val hours = (q.expiresAt - q.createdAt) / QuestRoll.HOUR
            val fields = DiscordFormat.fields(
                text("kami_economy.discord.quest_reward") to DiscordFormat.money(q.total),
                text("kami_economy.discord.quest_price") to "${DiscordFormat.each(q.lotPrice.toDouble() / q.lot)}/item ${DiscordFormat.change(q.offset * 100)}",
                text("kami_economy.discord.quest_time") to "${hours}h"
            ) + listOfNotNull(q.boosts.takeIf { it.isNotEmpty() }?.let { DiscordFormat.wide(text("kami_economy.discord.quest_boosts"), boosts(q)) })
            DiscordApi.market(Card.compact(text("kami_economy.discord.quest_title", Rarity.emoji[q.rarity], rarity(q.rarity)), text("kami_economy.discord.quest_head", name(q.item), q.amount), color, fields, timestamp = now()))
        }
    }

    fun finished(q: Quest, board: List<Pair<String, Int>>) {
        val server = Notify.server
        if (server == null && !DiscordApi.ready) return
        safely("end") {
            val at = q.endedAt ?: now()
            val pct = q.fillPct
            if (q.state == QuestState.DONE) {
                val win = board.firstOrNull()
                val who = win?.let { "${DiscordFormat.country(ClaimsApi.country(it.first)?.name ?: it.first)} (${it.second}/${q.amount})" } ?: text("kami_economy.discord.quest_nobody")
                if (DiscordApi.marketOn) DiscordApi.market(Card.compact(text("kami_economy.discord.quest_done_title"), text("kami_economy.discord.quest_done", name(q.item), who, DiscordFormat.duration(at - q.createdAt)), Rarity.colors[q.rarity], footer = q.boosts.takeIf { it.isNotEmpty() }?.let { boosts(q) }))
                val country = win?.first ?: return@safely
                server?.playerList?.players?.filter { ClaimsApi.countryOf(it.uuid) == country }?.forEach { p ->
                    p.tell(chat.say(Tone.OK, Phrase.of("kami_economy.quest.won", Notify.stack(q.item, q.amount))))
                    q.boosts.forEach { b -> p.tell(chat.say(Tone.OK, Phrase.of("kami_economy.quest.boost", b.pct, Phrase.of("kami_economy.quest.kind.${b.kind}"), b.hours))) }
                }
            } else if (q.state == QuestState.EXPIRED && DiscordApi.marketOn) {
                DiscordApi.market(Card.compact(text("kami_economy.discord.quest_expired_title"), text("kami_economy.discord.quest_expired", name(q.item), pct), Rarity.colors[q.rarity]))
            }
        }
    }
}
