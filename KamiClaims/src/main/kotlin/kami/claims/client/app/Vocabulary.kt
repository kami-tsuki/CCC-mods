package kami.claims.client.app

import kami.claims.gov.Perm
import kami.claims.gov.PermGroup
import kami.claims.net.Info
import kami.claims.net.RankLine
import kami.claims.TaxMode
import kami.claims.tributeOwed
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.UiRisk
import kotlin.math.abs

class Look(private val key: String, private val pick: () -> Int, val icon: Icon, private val fallback: String = key, private val fallbackDescription: String = "") {
    constructor(key: String, color: Int, icon: Icon, fallback: String = key, fallbackDescription: String = "") : this(key, { color }, icon, fallback, fallbackDescription)

    val color get() = pick()
    val label: String get() = trOr(key, fallback)
    val description: String get() = trOr("$key.desc", fallbackDescription)
}

object Vocabulary {
    private const val ADMIN_AUDIT_PREFIX = "admin_"

    private fun type(id: String, icon: Icon) = id to Look("kami_claims.chunk_type.$id", { Palette.chunkType(id) ?: Palette.textMuted }, icon)

    private val types = mapOf(
        type("civic", Icons.TOWN),
        type("mining", Icons.PICKAXE),
        type("farming", Icons.WHEAT),
        type("forestry", Icons.TREE),
        type("factory", Icons.GEAR),
        type("market", Icons.SCALES),
        type("residential", Icons.HOUSE),
        type("infrastructure", Icons.RAIL),
        type("wilderness", Icons.PINE)
    )

    fun type(name: String): Look = types[name] ?: Look(
        "kami_claims.chunk_type.$name", Palette.chart.let { c -> if (c.isEmpty()) Palette.textMuted else c[abs(name.hashCode()) % c.size] }, Icons.GENERIC,
        name.replaceFirstChar { it.uppercase() }, tr("kami_claims.chunk_type.custom.desc")
    )

    fun standing(name: String): Look = when (name.lowercase()) {
        "allied" -> Look("kami_claims.rank.allied", Palette.geoAlly, Icons.HANDSHAKE)
        "banished" -> Look("kami_claims.rank.banished", Palette.danger, Icons.BAN)
        else -> Look(name, Palette.textMuted, Icons.PERSON, name)
    }

    private fun group(group: PermGroup, icon: Icon) = group to Look("kami_claims.perm_group.${group.name.lowercase()}", 0, icon)

    private val groups = mapOf(
        group(PermGroup.LAND, Icons.AREA),
        group(PermGroup.TREASURY, Icons.TREASURY),
        group(PermGroup.PEOPLE, Icons.PEOPLE),
        group(PermGroup.RANKS, Icons.STAR),
        group(PermGroup.JOBS, Icons.TOOL),
        group(PermGroup.USE, Icons.HAND),
        group(PermGroup.COUNTRY, Icons.FLAG),
        group(PermGroup.NEWS, Icons.SCROLL)
    )

    fun group(group: PermGroup): Look = groups.getValue(group)

    fun perm(perm: Perm): Look = Look("kami_claims.perm.${perm.node}", 0, group(perm.group).icon)

    fun risk(perm: Perm): UiRisk = UiRisk.valueOf(perm.risk.name)

    private val accessIds = listOf("none", "officer", "assigned", "job", "worker", "citizen", "allied", "any")

    private fun access(id: String, icon: Icon) = id to Look("kami_claims.access.$id", { Palette.mix(Palette.danger, Palette.success, accessIds.indexOf(id) / accessIds.lastIndex.toFloat()) }, icon)

    val access = listOf(
        access("none", Icons.BAN),
        access("officer", Icons.SHIELD),
        access("assigned", Icons.PIN),
        access("job", Icons.PICKAXE),
        access("worker", Icons.TOOL),
        access("citizen", Icons.PEOPLE),
        access("allied", Icons.HANDSHAKE),
        access("any", Icons.GLOBE)
    )

    fun access(name: String) = access.firstOrNull { it.first == name }?.second ?: access.first().second

    fun accessOptions() = access.map { (value, look) -> Option(value, look.label, look.icon, look.description, look.color) }

    val actions = listOf(
        Look("kami_claims.action.break", 0, Icons.PICKAXE),
        Look("kami_claims.action.place", 0, Icons.BLOCK),
        Look("kami_claims.action.interact", 0, Icons.HAND),
        Look("kami_claims.action.container", 0, Icons.CHEST)
    )

    val flags = listOf(
        "machines" to Look("kami_claims.flag.machines", 0, Icons.GEAR),
        "fire" to Look("kami_claims.flag.fire", 0, Icons.FIRE)
    )

    private fun entry(id: String, icon: Icon) = id to Look("kami_claims.ledger.$id", 0, icon)

    val ledger = mapOf(
        entry("deposit", Icons.DEPOSIT),
        entry("withdraw", Icons.WITHDRAW),
        entry("claim", Icons.AREA),
        entry("upkeep", Icons.EXPENSE),
        entry("plot_tax", Icons.HOUSE),
        entry("job_pay", Icons.TOOL),
        entry("tribute_in", Icons.CHAIN),
        entry("tribute_out", Icons.CHAIN),
        entry("adjust", Icons.EDIT),
        entry("tariff", Icons.SCALES),
        entry("war_fee", Icons.FLAG),
        entry("war_stake", Icons.SHIELD),
        entry("war_bill", Icons.SCROLL),
        entry("war_debt", Icons.DEBT)
    )

    fun ledger(kind: String) = ledger[kind] ?: Look("kami_claims.ledger.$kind", 0, Icons.GENERIC, kind)

    fun job(id: String) = id.replaceFirstChar(Char::uppercase)

    fun audit(action: String): String {
        val code = action.removePrefix(ADMIN_AUDIT_PREFIX)
        val label = trOr("kami_claims.audit.$code", code)
        return if (code == action) label else tr("kami_claims.audit.admin_prefix", label)
    }

    fun rate(price: Int, period: Int): String {
        val amount = Format.money(price.toLong())
        return if (period <= 1) Format.perDay(amount) else tr("kami_claims.rate.every", amount, tr("kami_claims.unit.day.other", Format.number(period)))
    }

    fun relationColor(relation: Int) = when (relation) {
        1 -> Palette.success
        2 -> Palette.geoAlly
        3 -> Palette.geoProvince
        4 -> Palette.geoHostile
        5 -> Palette.danger
        else -> Palette.geoNeutral
    }

    fun relationLabel(relation: Int) = tr("kami_claims.relation.${relation.coerceIn(0, 5)}")

    fun relation(key: String) = when (key) {
        "own" -> Palette.success to tr("kami_claims.relation.1")
        "family" -> Palette.geoProvince to tr("kami_claims.relation.3")
        "ally" -> Palette.geoAlly to tr("kami_claims.relation.2")
        "banished" -> Palette.danger to tr("kami_claims.relation.4")
        "enemy" -> Palette.danger to tr("kami_claims.relation.5")
        else -> Palette.textMuted to tr("kami_claims.relation.0")
    }

    fun trade(trade: String, alliance: String) = when {
        trade == "family" -> Palette.geoProvince to tr("kami_claims.relation.3")
        trade == "embargo" -> Palette.danger to tr("kami_libs.common.embargo")
        alliance == "allied" -> Palette.geoAlly to tr("kami_claims.relation.2")
        alliance == "offer_in" -> Palette.warning to tr("kami_claims.trade.offer_in")
        alliance == "offer_out" -> Palette.textMuted to tr("kami_claims.trade.offer_out")
        else -> Palette.textMuted to tr("kami_claims.relation.0")
    }

    fun perDay(amount: Double, period: Int = 1) = amount / period.coerceAtLeast(1)

    fun tributeEstimate(mode: String, amount: Double, base: Long) = tributeOwed(if (mode == "percent") TaxMode.PERCENT else TaxMode.FLAT, amount, base)

    fun tribute(mode: String, amount: Double) =
        if (mode == "percent") tr("kami_claims.tribute.percent", Format.number((amount * 100).toLong())) else Format.perDay(Format.money(amount.toLong()))

    fun ratio(a: Number, b: Number) = tr("kami_libs.format.ratio", Format.number(a.toLong()), Format.number(b.toLong()))
}

fun permsOf(names: Collection<String>): Set<Perm> = Perm.entries.filterTo(mutableSetOf()) { it.name in names }

fun Info.rankLine(id: String): RankLine? = ranks.firstOrNull { it.id == id }

val RankLine.tint get() = Palette.opaque(color)
