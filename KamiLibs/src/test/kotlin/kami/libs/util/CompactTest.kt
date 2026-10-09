package kami.libs.util

import kotlin.test.Test
import kotlin.test.assertEquals

class CompactTest {
    @Test
    fun smallValuesStayExact() {
        assertEquals("999", Compact.of(999))
        assertEquals("-999", Compact.of(-999))
    }

    @Test
    fun valuesPromoteToTheNextUnitAtTheRoundingEdge() {
        assertEquals("999.9k", Compact.of(999_949))
        assertEquals("1M", Compact.of(999_950))
        assertEquals("1B", Compact.of(999_950_000))
    }

    @Test
    fun negativesKeepTheSignAndTheLargestUnitDoesNotPromote() {
        assertEquals("-1.5k", Compact.of(-1_500))
        assertEquals("1000B", Compact.of(1_000_000_000_000))
    }

    @Test
    fun scaledUsesTheGivenDecimalSeparator() {
        assertEquals("1,5", Compact.scaled(1500, 1000, ','))
        assertEquals("2", Compact.scaled(2000, 1000))
    }
}
