package kami.claims.gov

import kami.claims.service.Fail
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RankThrottleTest {
    @BeforeTest
    @AfterTest
    fun clear() = RankActions.reset()

    @Test
    fun theTwentyFirstCallFailsButAnotherActorOrCountryPasses() {
        repeat(20) { RankActions.throttle("land:a") }
        assertFailsWith<Fail> { RankActions.throttle("land:a") }
        RankActions.throttle("land:b")
        RankActions.throttle("other:a")
    }
}
