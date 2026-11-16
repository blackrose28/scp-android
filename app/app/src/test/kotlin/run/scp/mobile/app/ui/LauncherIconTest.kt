package run.scp.mobile.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LauncherIconTest {
    private fun res(name: String) = File("src/main/res/$name")

    private val manifest get() = File("src/main/AndroidManifest.xml").readText()

    @Test fun `the application declares a launcher icon`() {
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in manifest, "manifest must set android:icon")
        assertTrue("android:roundIcon=\"@mipmap/ic_launcher_round\"" in manifest, "manifest must set android:roundIcon")
    }

    @Test fun `both icon entry points are adaptive icons with foreground, background and monochrome layers`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val f = res("mipmap-anydpi-v26/$name.xml")
            assertTrue(f.exists(), "$name.xml is missing")
            val xml = f.readText()
            assertTrue("<adaptive-icon" in xml)
            assertTrue("<foreground android:drawable=\"@drawable/ic_launcher_foreground\"" in xml)
            assertTrue("<background android:drawable=\"@color/ic_launcher_background\"" in xml)
            assertTrue("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\"" in xml)
        }
    }

    @Test fun `the referenced icon resources exist`() {
        assertTrue(res("drawable/ic_launcher_foreground.xml").exists())
        assertTrue(res("drawable/ic_launcher_monochrome.xml").exists())
        assertTrue("name=\"ic_launcher_background\"" in res("values/ic_launcher_background.xml").readText())
    }
}
