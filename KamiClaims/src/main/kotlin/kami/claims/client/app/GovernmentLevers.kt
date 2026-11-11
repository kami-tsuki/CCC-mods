package kami.claims.client.app

import kami.claims.gov.Labour
import kami.claims.gov.Land
import kami.claims.gov.Leadership
import kami.claims.gov.Levers
import kami.claims.gov.Membership
import kami.claims.gov.ProvinceRule
import kami.claims.gov.RankStructure
import kami.claims.gov.TaxStyle
import kami.claims.gov.TreasuryControl
import kami.claims.net.GovLine
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Option

internal object GovernmentLevers {
    private inline fun <reified E : Enum<E>> names() = enumValues<E>().map { it.name.lowercase() }

    const val PROVINCES = "provinces"

    val all = listOf(
        "labour" to names<Labour>(),
        "land" to names<Land>(),
        "taxes" to names<TaxStyle>(),
        "treasury" to names<TreasuryControl>(),
        "leadership" to names<Leadership>(),
        "ranks" to names<RankStructure>(),
        PROVINCES to names<ProvinceRule>(),
        "membership" to names<Membership>()
    )

    fun values(gov: GovLine) = listOf(gov.labour, gov.land, gov.taxes, gov.treasury, gov.leadership, gov.ranks, gov.provinces, gov.membership)

    fun values(levers: Levers) = listOf<Enum<*>>(levers.labour, levers.land, levers.taxes, levers.treasury, levers.leadership, levers.ranks, levers.provinces, levers.membership).map { it.name.lowercase() }

    fun label(lever: String) = tr("kami_claims.lever.$lever")

    fun valueLabel(lever: String, value: String) = tr("kami_claims.lever.$lever.$value")

    fun options(lever: String) = all.first { it.first == lever }.second.map { Option(it, valueLabel(lever, it)) }

    fun keeping(values: List<String>, from: List<String>, lever: String) = all.indexOfFirst { it.first == lever }.let { with(values, it, from[it]) }

    fun with(values: List<String>, index: Int, value: String) = values.toMutableList().also { it[index] = value }
}
