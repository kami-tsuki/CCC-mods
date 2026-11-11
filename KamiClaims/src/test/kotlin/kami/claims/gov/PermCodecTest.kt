package kami.claims.gov

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermCodecTest : GovTest() {
    @Test
    fun parsingIgnoresCaseWhitespaceAndBlankTokens() {
        assertEquals(setOf(Perm.CLAIM, Perm.KICK), PermCodec.parse("claim, KICK,,"))
        assertEquals(emptySet(), PermCodec.parse(""))
    }

    @Test
    fun unknownPermsAreRejected() {
        assertTrue(failure { PermCodec.parse("claim,flying") }.contains("rank_perm"))
    }

    @Test
    fun diffListsAdditionsBeforeRemovals() {
        assertEquals("+${Perm.CLAIM.node} -${Perm.KICK.node}", PermCodec.diff(setOf(Perm.CLAIM), setOf(Perm.KICK)))
        assertEquals("", PermCodec.diff(emptySet(), emptySet()))
    }
}
