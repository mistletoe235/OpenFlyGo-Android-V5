package edu.playground.djivln.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAwakeLayoutTest {
    @Test
    fun activityRootKeepsAllFlightAndSurveyViewsAwake() {
        val current = File(requireNotNull(System.getProperty("user.dir")))
        val project = listOf(current, current.parentFile).first { File(it, "app/build.gradle").isFile }
        val layouts = File(project, "app/src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("layout") }
            .map { File(it, "activity_next_main.xml") }.filter(File::isFile)
        assertTrue(layouts.isNotEmpty())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        layouts.forEach { layout ->
            val root = factory.newDocumentBuilder().parse(layout).documentElement
            assertEquals(layout.path, "true", root.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "keepScreenOn",
            ))
        }
    }
}
