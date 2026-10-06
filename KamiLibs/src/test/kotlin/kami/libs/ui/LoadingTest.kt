package kami.libs.ui

import kami.libs.admin.AdminReply
import kami.libs.ui.app.Remote
import kami.libs.ui.widget.skeletonRowCount
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadingTest {
    @Test
    fun remoteCachesPerKey() {
        val sent = ArrayList<Pair<List<String>, (AdminReply) -> Unit>>()
        var key = listOf("a")
        val remote = Remote("x", String.serializer(), send = { _, args, done -> sent += args to done }) { key }
        fun reply(v: String) = sent.removeAt(sent.lastIndex).second(AdminReply(true, JsonPrimitive(v)))
        remote.poll()
        assertTrue(remote.loading)
        assertNull(remote.data)
        reply("A")
        assertEquals("A", remote.data)
        key = listOf("b")
        remote.poll()
        assertNull(remote.data)
        assertEquals("A", remote.latest)
        reply("B")
        key = listOf("a")
        remote.poll()
        assertEquals("A", remote.data)
        assertFalse(remote.loading)
        assertTrue(sent.isEmpty())
        remote.prefetch(listOf("c"))
        assertEquals(listOf("c"), sent.single().first)
        assertEquals("A", remote.data)
    }

    @Test
    fun skeletonRowsFillTheBody() {
        assertEquals(7, skeletonRowCount(100, 14))
        assertEquals(0, skeletonRowCount(10, 14))
        assertEquals(12, skeletonRowCount(1000, 14))
        assertEquals(0, skeletonRowCount(100, 0))
    }
}
