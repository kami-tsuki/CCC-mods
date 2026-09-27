package kami.libs.claims

import java.util.UUID

class ClaimInfo(val country: String, val type: String, val owner: UUID?)

class FlagInfo(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)

class CountryInfo(val id: String, val name: String, val color: Int, val flag: FlagInfo = FlagInfo())

class Citizenship(val country: String, val name: String, val color: Int, val parent: String?, val rank: String)

enum class Relation { NEUTRAL, ALLIED, FAMILY, EMBARGO }

enum class TreasuryKind { TARIFF }

interface ClaimsProvider {
    fun at(dim: String, x: Int, z: Int): ClaimInfo?
    fun isBanished(player: UUID, country: String): Boolean
    fun countryOf(player: UUID): String?
    fun citizenship(player: UUID): Citizenship?
    fun country(id: String): CountryInfo? = null
    fun relation(a: String, b: String): Relation = Relation.NEUTRAL
    fun tariff(buyerCountry: String, sellerCountry: String): Int = 0
    fun credit(country: String, amount: Long, kind: TreasuryKind): Boolean = false
}

object ClaimsApi {
    private var provider: ClaimsProvider? = null

    fun register(p: ClaimsProvider?) {
        provider = p
    }

    val present get() = provider != null

    fun at(dim: String, x: Int, z: Int): ClaimInfo? = provider?.at(dim, x, z)
    fun isBanished(player: UUID, country: String): Boolean = provider?.isBanished(player, country) ?: false
    fun countryOf(player: UUID): String? = provider?.countryOf(player)
    fun citizenship(player: UUID): Citizenship? = provider?.citizenship(player)
    fun country(id: String): CountryInfo? = provider?.country(id)
    fun relation(a: String, b: String): Relation = provider?.relation(a, b) ?: Relation.NEUTRAL
    fun tariff(buyerCountry: String, sellerCountry: String): Int = provider?.tariff(buyerCountry, sellerCountry) ?: 0
    fun canTrade(a: String, b: String): Boolean = relation(a, b) != Relation.EMBARGO
    fun credit(country: String, amount: Long, kind: TreasuryKind): Boolean = provider?.credit(country, amount, kind) ?: false
}
