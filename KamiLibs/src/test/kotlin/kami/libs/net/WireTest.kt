package kami.libs.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class WireTest {
    private enum class Mode { A, B }

    private fun reader(write: WireWriter.() -> Unit) = WireReader(WireWriter().apply(write).toBytes())

    @Test
    fun varIntRoundTripsBoundaryValues() {
        val r = reader { varInt(0); varInt(127); varInt(128); varInt(-1) }
        assertEquals(listOf(0, 127, 128, -1), List(4) { r.varInt() })
    }

    @Test
    fun readerRejectsOversizedCountsFlagsTextAndEnums() {
        assertEquals(5, reader { varInt(5) }.count(5))
        assertFailsWith<IllegalArgumentException> { reader { varInt(6) }.count(5) }
        assertFailsWith<IllegalArgumentException> { reader { varInt(2) }.bool() }
        assertFailsWith<IllegalArgumentException> { reader { utf("abcdef", 6) }.utf(5) }
        assertEquals(Mode.B, reader { varInt(1) }.enum(Mode.entries))
        assertFailsWith<IllegalArgumentException> { reader { varInt(2) }.enum(Mode.entries) }
        assertFailsWith<IllegalArgumentException> { WireWriter().utf("abcdef", 5) }
    }

    @Test
    fun blobRoundTripsBelowAndAboveDeflateThreshold() {
        val small = ByteArray(100) { it.toByte() }
        val big = ByteArray(DEFLATE_ABOVE + 10) { (it % 7).toByte() }
        assertContentEquals(small, Blob.unpack(Blob.pack(small), 1 shl 22))
        val packed = Blob.pack(big)
        assertEquals(true, packed.size < big.size)
        assertContentEquals(big, Blob.unpack(packed, 1 shl 22))
    }

    @Test
    fun blobRejectsOversizeAndTruncatedInput() {
        val data = ByteArray(1000)
        assertFailsWith<IllegalArgumentException> { Blob.unpack(Blob.pack(data), 999) }
        assertFails { Blob.unpack(Blob.pack(data).copyOf(10), 1 shl 22) }
    }
}
