package run.scp.mobile.app.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportTest {
    @Test fun `at the bottom the window is the last rows`() {
        assertEquals(76, Viewport.firstLine(totalLines = 100, rows = 24, scrolledBack = 0))
    }

    @Test fun `scrolling back moves the window up`() {
        assertEquals(66, Viewport.firstLine(100, 24, 10))
    }

    @Test fun `the window never starts before the first line`() {
        assertEquals(0, Viewport.firstLine(100, 24, 500))
        assertEquals(0, Viewport.firstLine(10, 24, 0))
    }

    @Test fun `scroll position clamps to the available history`() {
        assertEquals(76, Viewport.clamp(1000, totalLines = 100, rows = 24))
        assertEquals(0, Viewport.clamp(-5, 100, 24))
        assertEquals(0, Viewport.clamp(3, 20, 24))
    }

    @Test fun `a user at the bottom stays at the bottom as output arrives`() {
        assertEquals(0, Viewport.anchored(0, oldTotal = 50, newTotal = 60, rows = 24))
    }

    @Test fun `a user reading history keeps the same lines in view as output arrives`() {
        assertEquals(15, Viewport.anchored(5, oldTotal = 50, newTotal = 60, rows = 24))
    }

    @Test fun `anchoring never goes past the available history`() {
        assertEquals(36, Viewport.anchored(40, oldTotal = 50, newTotal = 60, rows = 24))
    }
}
