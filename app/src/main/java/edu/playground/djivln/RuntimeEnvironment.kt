package edu.playground.djivln

import android.os.Build

object RuntimeEnvironment {
    val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
    }
}
