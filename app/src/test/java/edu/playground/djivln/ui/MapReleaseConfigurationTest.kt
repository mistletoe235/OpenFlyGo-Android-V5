package edu.playground.djivln.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MapReleaseConfigurationTest {
    @Test fun mapSdkNativeAndReflectiveClassesAreKeptByConsumingReleaseApps() {
        val current = File(requireNotNull(System.getProperty("user.dir")))
        val root = listOf(current, current.parentFile).first { File(it, "uxsdk/build.gradle").isFile }
        val configuration = File(root, "uxsdk/build.gradle").readText()
        val rules = File(root, "uxsdk/consumer-rules.pro").readText()
        assertTrue(configuration.contains("consumerProguardFiles 'consumer-rules.pro'"))
        assertTrue(rules.contains("-keep class com.baidu.** { *; }"))
        assertTrue(rules.contains("-keep class vi.com.** { *; }"))
    }
}
