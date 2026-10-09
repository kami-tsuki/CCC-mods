package kami.claims.discord

import kami.claims.Config
import kami.claims.Country
import kami.claims.KamiClaims
import kami.claims.Rank
import kami.claims.Realm
import kami.claims.Tenancy
import kami.claims.now
import kami.claims.research.Loans
import kami.claims.service.Announce
import kami.claims.net.Sync
import kami.claims.service.View
import kami.claims.war.Fame
import kami.claims.war.Power
import kami.claims.war.Wars
import kami.libs.discord.Card
import kami.libs.discord.DiscordFormat
import kami.libs.discord.DiscordText
import kami.libs.discord.EmbedField
import kami.libs.discord.Mc
import kami.libs.discord.SlashCommand
import kami.libs.discord.SlashCtx
import kami.libs.discord.SlashOption
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

object DiscordClaims {
    private const val CHOICES = 25
    private const val MAX_PLOTS = 5
    private const val MAX_WARS = 2

    fun register() {
        Mc.add(SlashCommand("country", "Show a country", listOf(SlashOption("name", "Country name, defaults to your own", required = false, complete = ::names)), public = true, run = ::country))
        Mc.add(SlashCommand("debts", "Your rent, prepaid credit and debts", linked = true, run = ::debts))
        Mc.section(::section)
    }

    private fun text(locale: String, key: String, vararg args: Any) = DiscordText.get(KamiClaims.ID, locale, "kami_claims.discord.$key", *args)

    private fun names(input: String): List<String> =
        Realm.data.countries.values.filter { it.active && it.name.contains(input, true) }.map { it.name }
            .sortedWith(compareBy({ !it.startsWith(input, true) }, { it.lowercase() })).take(CHOICES)

    private fun section(player: UUID, locale: String): List<EmbedField> {
        val id = player.toString()
        val c = Realm.of(id) ?: return emptyList()
        val rank = c.rank(id)?.let { DiscordText.get(KamiClaims.ID, locale, "kami_claims.rank.${it.name.lowercase()}") }
        val fields = mutableListOf(EmbedField(text(locale, "field.country"), listOfNotNull(DiscordFormat.country(c.name), rank).joinToString(" · ")))
        if (View.privileged(c, id)) fields += EmbedField(text(locale, "field.treasury"), DiscordFormat.moneyFull(c.treasury))
        return fields
    }

    private fun country(ctx: SlashCtx) {
        val id = ctx.player?.toString()
        val named = ctx.opt("name")?.takeIf { it.isNotBlank() }
        val c = if (named != null) Realm.live(named) ?: return ctx.fail(text(ctx.locale, "country.unknown", DiscordFormat.literal(named))) else id?.let { Realm.of(it) }
            ?: return ctx.reply(text(ctx.locale, if (id == null) "country.link" else "country.none"))
        ctx.reply(card(c, ctx.locale))
    }

    private fun card(c: Country, locale: String): Card {
        val online = Announce.server?.playerList?.players?.count { it.stringUUID in c.members } ?: 0
        val head = listOf("${text(locale, "field.level")} ${c.level}", text(locale, "members", c.members.size, online), war(c, locale)).joinToString(" · ")
        val fields = DiscordFormat.fields(
            text(locale, "field.claims") to Realm.claimCount(c.id).toString(),
            text(locale, "field.provinces") to c.provinces.size.toString(),
            text(locale, "field.power") to DiscordFormat.num(Power.score(c).let { if (it.isFinite()) it.roundToInt().toLong() else 0L }),
            Fame.score(c)?.let { text(locale, "field.fame") to String.format(Locale.ROOT, "%.1f / 5", it) }
        )
        val footer = "${text(locale, "field.reputation")} ${whole(c.reputation)} · ${text(locale, "field.founded")}"
        return Card.compact(c.name, head, View.color(c), fields, footer, c.created)
    }

    private fun whole(n: Double) = if (n.isFinite()) n.roundToInt().toString() else "0"

    private fun war(c: Country, locale: String): String {
        val wars = Wars.all(c)
        if (wars.isEmpty()) return text(locale, "war.none")
        return wars.joinToString("\n") { w ->
            val other = Wars.name(if (w.attacker.main == c.id) w.defender.main else w.attacker.main)
            text(locale, "war.line", DiscordFormat.country(other), text(locale, "phase.${w.phase.name.lowercase()}"))
        }
    }

    private fun debts(ctx: SlashCtx) {
        val me = ctx.player?.toString() ?: return
        val locale = ctx.locale
        val day = Config.s.dayMillis
        val at = now()
        val plots = mutableListOf<String>()
        var rentDebt = 0L
        var earliest = Long.MAX_VALUE
        Sync.homes(me).forEach { h ->
            rentDebt += h.debt
            val parts = mutableListOf(text(locale, "plot.rent", DiscordFormat.money(h.rent), DiscordFormat.money(h.credit)))
            if (h.state == Tenancy.MOVING_OUT.name.lowercase()) parts += text(locale, "plot.moving", DiscordFormat.ago(h.until))
            else if (h.rent > 0) {
                val due = at + h.nextBill + h.credit / h.rent * day
                if (h.debt == 0L) earliest = minOf(earliest, due)
                parts += text(locale, "plot.covered", DiscordFormat.ago(due))
            }
            if (h.debt > 0) parts += text(locale, "plot.debt", DiscordFormat.money(h.debt))
            plots += "${DiscordFormat.country(h.country)} (${h.x}, ${h.z}) · ${parts.joinToString(" · ")}"
        }
        val fields = mutableListOf<EmbedField>()
        if (plots.isNotEmpty()) fields += DiscordFormat.wide(text(locale, "plots"), plots.take(MAX_PLOTS).joinToString("\n"))
        var owed = rentDebt
        val own = Realm.of(me)?.takeIf { (it.rank(me) ?: Rank.BANISHED) >= Rank.OFFICER }
        if (own != null) {
            if (own.loans.isNotEmpty()) {
                val overdue = own.loans.sumOf { it.overdue }
                owed += overdue
                fields += DiscordFormat.wide(text(locale, "loans"), text(locale, "loans.value", own.loans.size, DiscordFormat.money(own.loans.sumOf { Loans.remaining(it) }), DiscordFormat.money(overdue)))
            }
            if (own.warDebts.isNotEmpty()) {
                owed = own.warDebts.values.fold(owed) { a, b -> if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b }
                fields += DiscordFormat.wide(text(locale, "war_debts"), own.warDebts.entries.take(MAX_PLOTS).joinToString("\n") { "${DiscordFormat.country(Wars.name(it.key))} · ${DiscordFormat.money(it.value)}" })
            }
        }
        val head = buildList {
            if (earliest != Long.MAX_VALUE) add(text(locale, "next", DiscordFormat.ago(earliest)))
            if (rentDebt > 0) add(text(locale, "rent_debt", DiscordFormat.money(rentDebt)))
            if (isEmpty()) add(text(locale, if (fields.isEmpty()) "none" else "clear"))
        }.joinToString("\n")
        val more = if (plots.size > MAX_PLOTS) text(locale, "plot.more", plots.size - MAX_PLOTS) else null
        ctx.reply(Card.compact(text(locale, "debts.title"), head, if (owed > 0) DiscordFormat.BAD else DiscordFormat.GOOD, fields, more, now()))
    }
}
