package kami.libs.claims

import kami.libs.text.Phrase
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.UUID

class Goal(val text: Phrase, val value: Long, val max: Long, val page: String? = null)

class ClaimInfo(val country: String, val type: String, val owner: UUID?)

class FlagInfo(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)

class CountryInfo(val id: String, val name: String, val color: Int, val flag: FlagInfo = FlagInfo(), val treasury: Long = 0)

class Citizenship(val country: String, val name: String, val color: Int, val parent: String?, val rank: String)

enum class Relation { NEUTRAL, ALLIED, FAMILY, EMBARGO }

object CountryCapacity {
    const val MARKET_SLOTS = "marketSlots"
    const val AUCTION_SLOTS = "auctionSlots"
}

interface ClaimsProvider {
    fun at(dim: String, x: Int, z: Int): ClaimInfo?
    fun isBanished(player: UUID, country: String): Boolean
    fun countryOf(player: UUID): String?
    fun citizenship(player: UUID): Citizenship?
    fun country(id: String): CountryInfo? = null
    fun countries(): List<CountryInfo> = emptyList()
    fun relation(a: String, b: String): Relation = Relation.NEUTRAL
    fun tariff(buyerCountry: String, sellerCountry: String): Int = 0
    fun capacity(player: UUID, key: String): Int? = null
    fun creditTariff(country: String, amount: Long): Long = 0
    fun level(player: UUID): Int? = null
    fun lock(player: UUID, feature: String): Phrase? = null
    fun limit(player: UUID, key: String, used: Int): Phrase? = null
    fun goals(player: UUID): List<Goal> = emptyList()
    fun allowedRecipe(country: String?, recipe: ResourceLocation): Boolean = true
    fun allowedBlock(country: String?, block: ResourceLocation): Boolean = true
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
    fun isCitizen(player: UUID): Boolean = !present || countryOf(player) != null
    fun country(id: String): CountryInfo? = provider?.country(id)
    fun countries(): List<CountryInfo> = provider?.countries().orEmpty()
    fun relation(a: String, b: String): Relation = provider?.relation(a, b) ?: Relation.NEUTRAL
    fun tariff(buyerCountry: String, sellerCountry: String): Int = provider?.tariff(buyerCountry, sellerCountry) ?: 0
    fun capacity(player: UUID, key: String, fallback: Int): Int = provider?.capacity(player, key) ?: fallback
    fun canTrade(a: String, b: String): Boolean = relation(a, b) != Relation.EMBARGO
    fun neutralForeign(a: String, b: String): Boolean = a != b && relation(a, b) == Relation.NEUTRAL
    fun neutralForeign(a: UUID, b: UUID): Boolean {
        val ca = countryOf(a) ?: return false
        val cb = countryOf(b) ?: return false
        return neutralForeign(ca, cb)
    }
    fun embargoed(a: UUID, b: UUID): Boolean {
        val ca = countryOf(a) ?: return false
        val cb = countryOf(b) ?: return false
        return !canTrade(ca, cb)
    }
    fun creditTariff(country: String, amount: Long): Long = provider?.creditTariff(country, amount) ?: 0
    fun level(player: UUID): Int? = provider?.level(player)
    fun lock(player: UUID, feature: String): Phrase? = provider?.lock(player, feature)
    fun limit(player: UUID, key: String, used: Int): Phrase? = provider?.limit(player, key, used)
    fun goals(player: UUID): List<Goal> = provider?.goals(player).orEmpty()
    fun allowedRecipe(country: String?, recipe: ResourceLocation): Boolean = provider?.allowedRecipe(country, recipe) ?: true
    fun allowedBlock(country: String?, block: ResourceLocation): Boolean = provider?.allowedBlock(country, block) ?: true

    fun countryAt(level: Level, pos: BlockPos): String? =
        at(level.dimension().location().toString(), pos.x shr 4, pos.z shr 4)?.country
}
