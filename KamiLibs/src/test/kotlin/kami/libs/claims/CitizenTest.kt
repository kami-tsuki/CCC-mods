package kami.libs.claims

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CitizenTest {
    private val member = UUID.randomUUID()
    private val drifter = UUID.randomUUID()

    private val provider = object : ClaimsProvider {
        override fun at(dim: String, x: Int, z: Int): ClaimInfo? = null
        override fun isBanished(player: UUID, country: String) = false
        override fun countryOf(player: UUID) = if (player == member) "aurelia" else null
        override fun citizenship(player: UUID): Citizenship? = null
    }

    @AfterTest
    fun reset() = ClaimsApi.register(null)

    @Test
    fun everyoneTradesWithoutClaims() {
        ClaimsApi.register(null)
        assertTrue(ClaimsApi.isCitizen(drifter))
    }

    @Test
    fun onlyCountryMembersTradeWithClaims() {
        ClaimsApi.register(provider)
        assertTrue(ClaimsApi.isCitizen(member))
        assertFalse(ClaimsApi.isCitizen(drifter))
    }
}
