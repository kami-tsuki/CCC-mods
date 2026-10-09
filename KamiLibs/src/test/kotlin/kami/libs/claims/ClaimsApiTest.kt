package kami.libs.claims

import kotlin.test.Test
import kotlin.test.assertFalse

class ClaimsApiTest {
    @Test
    fun hostileDefaultsFalseWithoutProvider() {
        ClaimsApi.register(null)
        assertFalse(ClaimsApi.hostile("a", "b"))
    }
}
