package kami.claims.war

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChunkRefWireTest {
    private fun roundTrip(ref: ChunkRef) = assertEquals(ref, ChunkRef.parse(ref.wire()))

    @Test
    fun wireIsDimensionThenXThenZ() {
        assertEquals("minecraft:overworld:3:-4", ChunkRef("minecraft:overworld", 3, -4).wire())
    }

    @Test
    fun overworldNetherAndCustomDimensionsRoundTrip() {
        roundTrip(ChunkRef("minecraft:overworld", 0, 0))
        roundTrip(ChunkRef("minecraft:the_nether", 12, 7))
        roundTrip(ChunkRef("mod:sub:dim", 1, 2))
    }

    @Test
    fun negativeCoordinatesRoundTrip() {
        roundTrip(ChunkRef("minecraft:overworld", -1, -2))
        roundTrip(ChunkRef("minecraft:overworld", Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun malformedTextIsNull() {
        assertNull(ChunkRef.parse(""))
        assertNull(ChunkRef.parse("garbage"))
        assertNull(ChunkRef.parse("minecraft:overworld:x:4"))
        assertNull(ChunkRef.parse("minecraft:overworld:3:"))
    }
}
