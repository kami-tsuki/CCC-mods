package kami.claims.press

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewsDigestTest {
    @Test
    fun skipsDuplicates() {
        val items = listOf(listOf("flagChanged", "Land"))
        assertFalse(NewsDigest.accepts(items, listOf("flagChanged", "Land")))
        assertTrue(NewsDigest.accepts(items, listOf("flagChanged", "Other")))
    }

    @Test
    fun capsPerTemplate() {
        val items = (1..40).map { listOf("flagChanged", "c$it") }
        assertFalse(NewsDigest.accepts(items, listOf("flagChanged", "new")))
        assertTrue(NewsDigest.accepts(items, listOf("renamed", "new")))
    }
}
