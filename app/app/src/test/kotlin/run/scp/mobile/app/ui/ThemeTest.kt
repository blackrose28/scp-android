package run.scp.mobile.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {
    private fun res(name: String) = File("src/main/res/$name").readText()

    private fun colorResource(name: String): Int {
        val hex = Regex("""<color name="$name">#([0-9A-Fa-f]{8})</color>""").find(res("values/colors.xml"))?.groupValues?.get(1)
            ?: error("colors.xml has no $name")
        return hex.toLong(16).toInt()
    }

    @Test fun `window background is the same colour the Compose theme paints`() {
        assertEquals(ScpColors.BACKGROUND, colorResource("scp_background"))
    }

    @Test fun `the activity theme uses that colour as its window background`() {
        assertTrue("@color/scp_background" in res("values/themes.xml"), "themes.xml must set windowBackground to @color/scp_background")
    }

    @Test fun `the app background is the terminal background so the screens do not clash`() {
        assertEquals(TERMINAL_BG, ScpColors.BACKGROUND)
    }
}
