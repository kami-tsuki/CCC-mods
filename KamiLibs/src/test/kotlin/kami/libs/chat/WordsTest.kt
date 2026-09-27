package kami.libs.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class WordsTest {
    @Test
    fun durationsPickTheTwoLargestUnits() {
        assertEquals("kami_libs.unit.minute.short", duration(59).key)
        val hours = duration(3_660)
        assertEquals(listOf("kami_libs.unit.hour.short", "kami_libs.unit.minute.short"), hours.args.map { it.key })
        assertEquals(listOf("1", "1"), hours.args.map { it.args.single().text })
        assertEquals(listOf("2", "3"), duration(2 * 86_400L + 3 * 3600).args.map { it.args.single().text })
    }
}
