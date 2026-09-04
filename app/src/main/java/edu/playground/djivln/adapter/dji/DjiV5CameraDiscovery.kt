package edu.playground.djivln.adapter.dji

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import edu.playground.djivln.survey.CameraProfile
import edu.playground.djivln.survey.DjiCameraProfileCatalog
import edu.playground.djivln.R

/** Discovers the connected camera instead of assuming one aircraft/camera index. */
class DjiV5CameraDiscovery(
    private val keyManager: KeyManager = KeyManager.getInstance(),
    private val context: Context? = null,
) {
    data class Selection(
        val index: ComponentIndexType,
        val cameraConnected: Boolean,
        val productType: String?,
        val cameraType: String?,
        val streamSource: CameraVideoStreamSourceType?,
        val availableStreamSources: List<CameraVideoStreamSourceType>,
        val cameraProfile: CameraProfile,
        val cameraProfileLabel: String,
        val profileVerified: Boolean,
        val officialSourceUrl: String?,
        val minimumIntervalFromAircraftSeconds: Double?,
    )

    @Volatile private var lastConnectedIndex = ComponentIndexType.LEFT_OR_MAIN
    @Volatile private var lastDiagnosticSummary: String? = null

    fun current(): Selection {
        val overview = runCatching {
            keyManager.getValue(KeyTools.createKey(ProductKey.KeyProductCameraCOverview)).orEmpty()
        }.getOrDefault(emptyList())
        val overviewIndices = overview.mapNotNull { it.index }.filter { it != ComponentIndexType.UNKNOWN }
        val candidates = (overviewIndices + CAMERA_CANDIDATES).distinct()
        val connected = candidates.filter(::isConnected)
        val index = when {
            lastConnectedIndex in connected -> lastConnectedIndex
            connected.isNotEmpty() -> connected.first()
            overviewIndices.isNotEmpty() -> overviewIndices.first()
            else -> ComponentIndexType.LEFT_OR_MAIN
        }
        if (index in connected) lastConnectedIndex = index
        val productType = runCatching {
            keyManager.getValue(KeyTools.createKey(ProductKey.KeyProductType))?.name
        }.getOrNull()
        val cameraType = runCatching {
            keyManager.getValue(KeyTools.createKey(CameraKey.KeyCameraType, index))?.name
        }.getOrNull() ?: overview.firstOrNull { it.index == index }?.cameraType?.name
        val availableStreamSources = runCatching {
            keyManager.getValue(
                KeyTools.createKey(CameraKey.KeyCameraVideoStreamSourceRange, index),
            )?.toList().orEmpty()
        }.getOrDefault(emptyList()).selectableStreamSources()
        val streamSource = runCatching {
            keyManager.getValue(KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, index))
        }.getOrNull()
        val resolved = DjiCameraProfileCatalog.resolve(productType, cameraType, streamSource?.name, context = context)
        return Selection(
            index = index,
            cameraConnected = index in connected,
            productType = productType,
            cameraType = cameraType,
            streamSource = streamSource,
            availableStreamSources = availableStreamSources,
            cameraProfile = resolved.profile,
            cameraProfileLabel = resolved.displayName,
            profileVerified = resolved.verifiedProfile,
            officialSourceUrl = resolved.officialSourceUrl,
            minimumIntervalFromAircraftSeconds = null,
        ).also(::logWhenChanged)
    }

    fun selectNextStreamSource(completion: (Result<CameraVideoStreamSourceType>) -> Unit) {
        val selection = current()
        val sources = selection.availableStreamSources
        if (selection.index == ComponentIndexType.UNKNOWN) {
            completion(Result.failure(IllegalStateException(text(R.string.camera_index_unavailable, "Camera index is unavailable"))))
            return
        }
        if (sources.isEmpty()) {
            completion(Result.failure(IllegalStateException(text(R.string.camera_no_switchable_lens, "The current camera has no switchable lens source"))))
            return
        }
        val currentPosition = sources.indexOf(selection.streamSource)
        val next = sources[if (currentPosition < 0) 0 else (currentPosition + 1) % sources.size]
        runCatching {
            keyManager.setValue(
                KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, selection.index),
                next,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = completion(Result.success(next))
                    override fun onFailure(error: IDJIError) = completion(
                        Result.failure(
                            IllegalStateException(
                                context?.getString(R.string.camera_lens_switch_failed_detail, error.toString())
                                    ?: "Lens switch failed: $error",
                            ),
                        ),
                    )
                },
            )
        }.onFailure { completion(Result.failure(it)) }
    }

    private fun text(resourceId: Int, fallback: String): String = context?.getString(resourceId) ?: fallback

    fun connectedIndices(): List<ComponentIndexType> = CAMERA_CANDIDATES.filter(::isConnected)

    private fun isConnected(index: ComponentIndexType): Boolean = runCatching {
        keyManager.getValue(KeyTools.createKey(CameraKey.KeyConnection, index)) == true
    }.getOrDefault(false)

    private fun logWhenChanged(selection: Selection) {
        val summary = buildString {
            append("index=${selection.index.name}")
            append(" connected=${selection.cameraConnected}")
            append(" product=${selection.productType ?: "--"}")
            append(" type=${selection.cameraType ?: "--"}")
            append(" source=${selection.streamSource?.name ?: "--"}")
            append(" sources=${selection.availableStreamSources.joinToString { it.name }.ifBlank { "--" }}")
            append(" profile=${selection.cameraProfile.id}")
            append(" verified=${selection.profileVerified}")
        }
        if (summary != lastDiagnosticSummary) {
            lastDiagnosticSummary = summary
            Log.i(TAG, summary)
        }
    }

    private fun List<CameraVideoStreamSourceType>.selectableStreamSources(): List<CameraVideoStreamSourceType> {
        val available = distinct().filter { it != CameraVideoStreamSourceType.UNKNOWN }
        return if (available.size > 1) {
            available.filter { it != CameraVideoStreamSourceType.DEFAULT_CAMERA }
        } else {
            available
        }
    }

    companion object {
        private const val TAG = "OpenFlyV5Camera"
        /**
         * Converts a camera component index to WPMZ's payloadPositionIndex.
         * MSDK 5 uses sparse values (20001+) for enterprise payload ports.
         */
        fun wpmzPayloadPositionIndex(index: ComponentIndexType): Int? =
            DjiWpmzPayloadPosition.fromComponentValue(index.value())

        /** Includes aircraft main cameras and common payload positions; FPV is intentionally excluded. */
        val CAMERA_CANDIDATES = listOf(
            ComponentIndexType.LEFT_OR_MAIN,
            ComponentIndexType.RIGHT,
            ComponentIndexType.UP,
            ComponentIndexType.INDEX_3,
            ComponentIndexType.UP_TYPE_C,
            ComponentIndexType.UP_TYPE_C_EXT_ONE,
            ComponentIndexType.PORT_1,
            ComponentIndexType.PORT_2,
            ComponentIndexType.PORT_3,
            ComponentIndexType.PORT_4,
            ComponentIndexType.PORT_5,
            ComponentIndexType.PORT_6,
            ComponentIndexType.PORT_7,
        )
    }
}
