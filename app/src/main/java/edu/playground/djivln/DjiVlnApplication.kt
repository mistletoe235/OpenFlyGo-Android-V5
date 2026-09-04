package edu.playground.djivln

import android.app.Application
import android.content.Context
import edu.playground.djivln.logging.AppDiagnosticLogger

class DjiVlnApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        AppDiagnosticLogger.initialize(this)
        // MSDK managers used by the first Activity require SDKManager.init() to have
        // completed. Keeping this bootstrap ordered avoids a cold-start race on both
        // Keep process startup limited to SDK bootstrap; optional UI work is deferred.
        loadDjiNativeLibraries()
        runCatching { DjiSdkBootstrap.init(this) }
            .onFailure { error ->
                AppDiagnosticLogger.error("DjiSdkBootstrap", "DJI SDK initialization failed", error)
                DjiSdkBootstrap.markUnavailable(error)
            }
    }

    private fun loadDjiNativeLibraries() {
        listOf("dataclx", "djisdk_jni").forEach { name ->
            runCatching { System.loadLibrary(name) }
                .onFailure { error ->
                    AppDiagnosticLogger.warn(
                        source = "DjiNativeLibrary",
                        message = "Native library load failed: $name",
                        error = error,
                    )
                }
        }
    }
}
