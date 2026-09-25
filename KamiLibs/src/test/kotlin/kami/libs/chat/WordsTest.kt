package kami.libs.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class WordsTest {
    @Test
    fun durations() {
        assertEquals("0m", duration(59))
        assertEquals("1h 1m", duration(3_660))
        assertEquals("2d 3h", duration(2 * 86_400L + 3 * 3600))
    }

    @Test
    fun plurals() {
        assertEquals("1 country", plural(1, "country", "countries"))
        assertEquals("2 deposits", plural(2, "deposit"))
    }
}
