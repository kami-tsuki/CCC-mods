package kami.claims.gov

import kotlin.test.Test
import kotlin.test.assertEquals

class JobDefineTest : GovTest() {
    private fun args(pay: Int) = listOf("j", "Miner", "", "", "mine:stone:5", pay.toString(), "10", "1", "fixed", "open")

    @Test
    fun editingWithoutChangingPayKeepsTheTierBase() {
        val c = country(mapOf("a" to PRESIDENT), name = "Define")
        val existing = JobSpec("j", "Miner", pay = 75, quota = 10, basePay = 100)
        val same = JobArgs.spec(c, args(75), existing, JobScope.COUNTRY, "")
        assertEquals(100, same.basePay)
        val changed = JobArgs.spec(c, args(80), existing, JobScope.COUNTRY, "")
        assertEquals(80, changed.basePay)
    }
}
