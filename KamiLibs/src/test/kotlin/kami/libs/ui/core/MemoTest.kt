package kami.libs.ui.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoTest {
    @Test
    fun rebuildsOnlyWhenInputsOrEpochChange() {
        val memo = Memo()
        var builds = 0
        fun get(v: Int) = memo.of(v) { builds++ }
        get(1); get(1)
        assertEquals(1, builds)
        get(2)
        assertEquals(2, builds)
        Memo.invalidate()
        get(2); get(2)
        assertEquals(3, builds)
    }
}
