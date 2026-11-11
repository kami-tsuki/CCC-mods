package kami.libs.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MasterDetailTest {
    @Test
    fun widthStaysWithinBounds() {
        val layout = MasterDetail(0.4, 160, 230)
        assertEquals(160, layout.width(100))
        assertEquals(200, layout.width(500))
        assertEquals(230, layout.width(2000))
    }

    @Test
    fun shrinkingWithExistingSelectionDoesNotOpenDetail() {
        val layout = MasterDetail(0.4, 160, 230)
        val selection = setOf<Any>("a")
        assertFalse(layout.track(false, selection))
        assertFalse(layout.track(true, selection))
        assertTrue(layout.track(true, setOf<Any>("b")))
    }

    @Test
    fun splitNeedsRoomForListAndPanel() {
        assertTrue(MasterDetail.fits(260 + 200 + 6, 200, 6))
        assertFalse(MasterDetail.fits(260 + 200 + 5, 200, 6))
    }
}
