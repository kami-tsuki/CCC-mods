package kami.claims.client.app

import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.Option
import kotlin.math.abs

class Look(private val key: String, val color: Int, val icon: Icon, private val fallback: String = key, private val fallbackDescription: String = "") {
    val label: String get() = trOr(key, fallback)
    val description: String get() = trOr("$key.desc", fallbackDescription)
}

object Vocabulary {
    private fun type(id: String, color: Long, icon: Icon) = id to Look("kami_claims.chunk_type.$id", color.toInt(), icon)

    private val types = mapOf(
        type("civic", 0xFF5F6678, Icons.TOWN),
        type("mining", 0xFFA8650F, Icons.PICKAXE),
        type("farming", 0xFF4A8F2A, Icons.WHEAT),
        type("forestry", 0xFF1F7A45, Icons.TREE),
        type("factory", 0xFF2A6FD0, Icons.GEAR),
        type("market", 0xFFB83F68, Icons.SCALES),
        type("residential", 0xFF8A4FC8, Icons.HOUSE),
        type("infrastructure", 0xFF5E6378, Icons.RAIL),
        type("wilderness", 0xFF3F7A3B, Icons.PINE)
    )

    fun type(name: String): Look = types[name] ?: Look(
        "kami_claims.chunk_type.$name", Palette.chart[abs(name.hashCode()) % Palette.chart.size], Icons.GENERIC,
        name.replaceFirstChar { it.uppercase() }, tr("kami_claims.chunk_type.custom.desc")
    )

    private fun rank(id: String, color: Long, icon: Icon) = id to Look("kami_claims.rank.$id", color.toInt(), icon)

    private val ranks = mapOf(
        rank("president", 0xFFA87A00, Icons.CROWN),
        rank("chancellor", 0xFF8A4FC8, Icons.SCROLL),
        rank("officer", 0xFF2F68C8, Icons.SHIELD),
        rank("citizen", 0xFF1E8A58, Icons.PERSON),
        rank("allied", 0xFF0F78B0, Icons.HANDSHAKE),
        rank("banished", 0xFFB02A2E, Icons.BAN)
    )

    fun rank(name: String): Look = ranks[name.lowercase()] ?: Look("kami_claims.rank.${name.lowercase()}", Palette.textMuted, Icons.PERSON, name)

    val rankOrder = listOf("citizen", "officer", "chancellor", "president")

    private fun access(id: String, color: Long, icon: Icon) = id to Look("kami_claims.access.$id", color.toInt(), icon)

    val access = listOf(
        access("none", 0xFFB02A2E, Icons.BAN),
        access("officer", 0xFFB85A1E, Icons.SHIELD),
        access("job", 0xFFA8680A, Icons.PICKAXE),
        access("worker", 0xFF8A7418, Icons.TOOL),
        access("citizen", 0xFF4A8F2A, Icons.PEOPLE),
        access("allied", 0xFF0F78B0, Icons.HANDSHAKE),
        access("any", 0xFF1E8A58, Icons.GLOBE)
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

    private fun cap(id: String, icon: Icon) = id to Look("kami_claims.cap.$id", 0, icon)

    val caps = linkedMapOf(
        cap("claim", Icons.AREA),
        cap("capital", Icons.CROWN),
        cap("tax", Icons.TAX),
        cap("rules", Icons.SHIELD),
        cap("withdraw", Icons.WITHDRAW),
        cap("invite", Icons.INVITE),
        cap("members", Icons.PEOPLE),
        cap("rank", Icons.STAR),
        cap("jobs", Icons.TOOL),
        cap("plot", Icons.HOUSE),
        cap("housing", Icons.HOUSE),
        cap("details", Icons.LEDGER),
        cap("province", Icons.CHAIN),
        cap("trade", Icons.SCALES),
        cap("research", Icons.TREE)
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
        entry("tariff", Icons.SCALES)
    )

    fun ledger(kind: String) = ledger[kind] ?: Look("kami_claims.ledger.$kind", 0, Icons.GENERIC, kind)

    fun job(id: String) = trOr("kami_claims.job.$id", id)

    fun rate(price: Int, period: Int): String {
        val amount = Format.money(price.toLong())
        return if (period <= 1) Format.perDay(amount) else tr("kami_claims.rate.every", amount, tr("kami_claims.unit.day.other", Format.number(period)))
    }

    fun relationColor(relation: Int) = when (relation) {
        1 -> Palette.success
        2 -> Palette.geoAlly
        3 -> Palette.geoProvince
        4 -> Palette.geoHostile
        else -> Palette.geoNeutral
    }

    fun relationLabel(relation: Int) = tr("kami_claims.relation.${relation.coerceIn(0, 4)}")

    fun relation(key: String) = when (key) {
        "own" -> Palette.success to tr("kami_claims.relation.1")
        "family" -> Palette.geoProvince to tr("kami_claims.relation.3")
        "ally" -> Palette.geoAlly to tr("kami_claims.relation.2")
        "banished" -> Palette.danger to tr("kami_claims.relation.4")
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

    fun tribute(mode: String, amount: Double) =
        if (mode == "percent") tr("kami_claims.tribute.percent", Format.number((amount * 100).toLong())) else tr("kami_claims.tribute.flat", Format.number(amount.toLong()))
}
