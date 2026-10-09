package kami.libs.discord

import kami.libs.economy.Movement
import kami.libs.economy.Numismatics
import kami.libs.economy.Statement
import kami.libs.stats.Stats
import kami.libs.util.Time.DAY_MS

internal object McCommands {
    private const val RECENT = 3

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
            val fields = DiscordFormat.fields(ctx.t("bal.day") to DiscordFormat.signed(day), ctx.t("bal.week") to DiscordFormat.signed(week)) + extra
            val head = "**${DiscordFormat.moneyFull(balance)}**" + if (recent.isEmpty()) "" else "\n$recent"
            ctx.reply(Card.compact(ctx.t("bal.title", DiscordFormat.player(name)), head, DiscordFormat.GOLD, fields, fields.drop(Card.COMPACT_FIELDS).joinToString(" · ") { "${it.name}: ${it.value}" }.ifEmpty { null }, now))
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

    private val STAT_KEYS = listOf("session.time", "combat.kill", "combat.death", "block.mine", "block.place", "market.trade", "market.volume")

    private fun stats(ctx: SlashCtx) {
        val id = ctx.player ?: return
        ctx.background {
            val now = System.currentTimeMillis()
            val (all, week) = Stats.totals(STAT_KEYS, listOf(null, now - 7 * DAY_MS), "p:$id").map { m -> { key: String -> m[key] ?: 0L } }
            fun field(name: String, show: ((String) -> Long) -> String) = EmbedField(ctx.t(name), ctx.t("stats.value", show(all), show(week)))
            fun pair(a: String, b: String): ((String) -> Long) -> String = { t -> "${DiscordFormat.num(t(a))} / ${DiscordFormat.num(t(b))}" }
            val fields = listOf(
                field("stats.playtime") { DiscordFormat.duration(it("session.time")) },
                field("stats.kills", pair("combat.kill", "combat.death")),
                field("stats.blocks", pair("block.mine", "block.place")),
                field("stats.trades") { "${DiscordFormat.num(it("market.trade"))} · ${DiscordFormat.money(it("market.volume"))}" }
            )
            ctx.reply(Card.compact(ctx.t("stats.title", DiscordFormat.player(Links.get(id)?.name.orEmpty())), fields = fields, footer = ctx.t("stats.footer"), timestamp = now))
        }
    }

    private fun alerts(ctx: SlashCtx) {
        val id = ctx.player ?: return
        ctx.opt("state")?.let { DiscordPrefs.set(id, DiscordPrefs.MARKET_ALERTS, it == "on") }
        ctx.reply(ctx.t(if (DiscordPrefs.on(id, DiscordPrefs.MARKET_ALERTS)) "alerts.on" else "alerts.off"))
    }
}
