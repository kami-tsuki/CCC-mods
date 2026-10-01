package kami.claims

import kami.claims.research.ContextStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeContextTest {
    private var lookups = 0
    private val stack = ContextStack { source, pos ->
        lookups++
        if (source == "nowhere") null else "$source@$pos"
    }

    @Test
    fun `outside any frame the stack is inactive and has no country`() {
        assertFalse(stack.active)
        assertNull(stack.country())
        stack.pop()
        assertEquals(0, stack.depth)
    }

    @Test
    fun `country is resolved lazily and once per frame`() {
        stack.push("level", 5)
        assertEquals(0, lookups)
        assertEquals("level@5", stack.country())
        assertEquals("level@5", stack.country())
        assertEquals(1, lookups)
    }

    @Test
    fun `nested frames shadow and restore the outer one`() {
        stack.push("outer", 1)
        stack.push("inner", 2)
        assertEquals("inner@2", stack.country())
        stack.pop()
        assertTrue(stack.active)
        assertEquals("outer@1", stack.country())
        stack.pop()
        assertFalse(stack.active)
        assertNull(stack.country())
    }

    @Test
    fun `an unresolved frame is active with no country`() {
        stack.push("nowhere")
        assertTrue(stack.active)
        assertNull(stack.country())
        assertNull(stack.country())
        assertEquals(1, lookups)
    }

    @Test
    fun `a reused slot does not keep the previous country`() {
        stack.push("first", 1)
        assertEquals("first@1", stack.country())
        stack.pop()
        stack.push("second", 2)
        assertEquals("second@2", stack.country())
    }

    @Test
    fun `the stack grows past its initial capacity`() {
        repeat(40) { stack.push("frame$it", it.toLong()) }
        assertEquals("frame39@39", stack.country())
        repeat(39) { stack.pop() }
        assertEquals("frame0@0", stack.country())
        assertEquals(1, stack.depth)
    }
}
