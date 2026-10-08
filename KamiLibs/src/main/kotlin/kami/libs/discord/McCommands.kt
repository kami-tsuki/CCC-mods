package kami.libs.discord

import kami.libs.economy.Movement
import kami.libs.economy.Numismatics
import kami.libs.economy.Statement
import kami.libs.stats.Stats
import kami.libs.util.Time.DAY_MS
import java.util.Locale

internal object McCommands {
    private const val RECENT = 5

    val all = listOf(
        SlashCommand("bal", "Show your balance and recent transactions", linked = true, run = ::bal),
        SlashCommand("stats", "Show your play statistics", linked = true, run = ::stats),
        SlashCommand(
            "alerts", "Show or change your market price alerts by direct message",
            listOf(SlashOption("state", "Turn alerts on or off", false, choices = listOf("on", "off"))), linked = true, run = ::alerts
        )
    )

    private fun SlashCtx.t(key: String, vararg args: Any) = text("kami_libs", "kami_libs.discord.mc.$key", *args)

    private fun bal(ctx: SlashCtx) {
        val id = ctx.player ?: return
        val balance = Numismatics.balance(id)
        val extra = Mc.fields(id, ctx.locale)
        val name = Links.get(id)?.name.orEmpty()
        ctx.background {
            val now = System.currentTimeMillis()
            val (day, week) = Statement.net(id, 1, 7)
            val recent = Statement.movements(id).take(RECENT).joinToString("\n") { "${DiscordFormat.signed(it.amount)} · ${Sanitize.outbound(ctx.label(it))} · ${DiscordFormat.ago(it.at)}" }
            val fields = listOf(
                EmbedField(ctx.t("bal.day"), DiscordFormat.signed(day)),
                EmbedField(ctx.t("bal.week"), DiscordFormat.signed(week)),
                EmbedField(ctx.t("bal.recent"), recent.ifEmpty { ctx.t("bal.none") }, false)
            ) + extra
            ctx.reply(Card(ctx.t("bal.title", name), DiscordFormat.money(balance), DiscordFormat.GOLD, fields, timestamp = now))
        }
    }

    private fun SlashCtx.label(m: Movement): String {
        val flow = text("kami_libs", "kami_libs.flow.${m.flow.name.lowercase()}")
        if (m.item.isEmpty()) return flow
        if (!m.item.startsWith(Statement.NOTE)) return "$flow: ${if (m.qty > 0) "${m.qty} x " else ""}${m.item}"
        val parts = m.item.removePrefix(Statement.NOTE).split('|')
        val key = "kami_libs.note.${parts[0]}"
        val note = text("kami_libs", key, *parts.drop(1).toTypedArray())
        return if (note == key) flow else "$flow: $note"
    }

    private val STAT_KEYS = listOf("session.time", "combat.kill", "combat.death", "mob.kill", "block.mine", "block.place", "market.trade", "market.volume")

    private fun stats(ctx: SlashCtx) {
        val id = ctx.player ?: return
        ctx.background {
            val now = System.currentTimeMillis()
            val (all, week) = Stats.totals(STAT_KEYS, listOf(null, now - 7 * DAY_MS), "p:$id").map { m -> { key: String -> m[key] ?: 0L } }
            fun field(name: String, inline: Boolean = true, show: ((String) -> Long) -> String) = EmbedField(ctx.t(name), ctx.t("stats.value", show(all), show(week)), inline)
            fun pair(a: String, b: String): ((String) -> Long) -> String = { t -> "${number(t(a))} / ${number(t(b))}" }
            val fields = listOf(
                field("stats.playtime") { DiscordFormat.duration(it("session.time")) },
                field("stats.kills", show = pair("combat.kill", "combat.death")),
                field("stats.mobs") { number(it("mob.kill")) },
                field("stats.blocks", show = pair("block.mine", "block.place")),
                field("stats.trades") { number(it("market.trade")) },
                field("stats.volume") { DiscordFormat.money(it("market.volume")) }
            )
            ctx.reply(Card(ctx.t("stats.title", Links.get(id)?.name.orEmpty()), color = DiscordFormat.NEUTRAL, fields = fields, timestamp = now))
        }
    }

    private fun number(n: Long) = String.format(Locale.ROOT, "%,d", n)

    private fun alerts(ctx: SlashCtx) {
        val id = ctx.player ?: return
        ctx.opt("state")?.let { DiscordPrefs.set(id, DiscordPrefs.MARKET_ALERTS, it == "on") }
        ctx.reply(ctx.t(if (DiscordPrefs.on(id, DiscordPrefs.MARKET_ALERTS)) "alerts.on" else "alerts.off"))
    }
}
