package kami.essentials.admin

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IpLinksTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun newlyBannedIpIsLinked() {
        assertEquals(listOf("1.1.1.1"), IpLinks.linked(listOf("1.1.1.1"), listOf("1.1.1.1"), emptyMap(), me) { false })
    }

    @Test
    fun ipAlreadyLinkedToAnotherBannedPlayerIsLinked() {
        val links = mapOf(other to setOf("2.2.2.2"))
        assertEquals(listOf("2.2.2.2"), IpLinks.linked(listOf("2.2.2.2"), emptyList(), links, me) { it == other })
    }

    @Test
    fun unrelatedManualIpBanIsNotLinked() {
        val links = mapOf(other to setOf("2.2.2.2"))
        assertEquals(emptyList(), IpLinks.linked(listOf("3.3.3.3", "2.2.2.2"), emptyList(), links, me) { false })
    }

    @Test
    fun ownLinksDoNotCount() {
        assertEquals(emptyList(), IpLinks.linked(listOf("4.4.4.4"), emptyList(), mapOf(me to setOf("4.4.4.4")), me) { true })
    }
}
