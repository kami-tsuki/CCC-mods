package kami.libs.claims

import java.util.UUID

class ClaimInfo(val country: String, val type: String, val owner: UUID?)

class Citizenship(val country: String, val name: String, val color: Int, val parent: String?, val rank: String)

interface ClaimsProvider {
    fun at(dim: String, x: Int, z: Int): ClaimInfo?
    fun isBanished(player: UUID, country: String): Boolean
    fun countryOf(player: UUID): String?
    fun citizenship(player: UUID): Citizenship?
}

object ClaimsApi {
    private var provider: ClaimsProvider? = null

    fun register(p: ClaimsProvider) {
        provider = p
    }

    val present get() = provider != null

    fun at(dim: String, x: Int, z: Int): ClaimInfo? = provider?.at(dim, x, z)
    fun isBanished(player: UUID, country: String): Boolean = provider?.isBanished(player, country) ?: false
    fun countryOf(player: UUID): String? = provider?.countryOf(player)
    fun citizenship(player: UUID): Citizenship? = provider?.citizenship(player)
}
