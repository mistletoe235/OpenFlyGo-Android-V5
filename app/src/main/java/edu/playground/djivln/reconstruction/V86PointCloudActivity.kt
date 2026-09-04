package edu.playground.djivln.reconstruction

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** Dedicated, file-backed mobile reviewer. It never holds the original PLY bytes in the Activity intent. */
class V86PointCloudActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "v86-ply-viewer").apply { isDaemon = true }
    }
    private lateinit var viewer: V86PointCloudView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var candidatesVisible = true
    private lateinit var riskButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 16)
        window.navigationBarColor = Color.rgb(7, 11, 16)
        viewer = V86PointCloudView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 11, 16))
            addView(viewer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xdd111827.toInt())
        }
        status = TextView(this).apply {
            text = getString(edu.playground.djivln.R.string.point_cloud_loading)
            textSize = 11f
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        topBar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun action(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 9f
            minWidth = 0
            minHeight = 0
            setOnClickListener { onClick() }
            topBar.addView(this, LinearLayout.LayoutParams(dp(72), dp(38)).apply { marginStart = dp(5) })
        }
        action(getString(edu.playground.djivln.R.string.point_cloud_fit)) { viewer.setPreset(V86PointCloudView.ViewPreset.ISOMETRIC) }
        action(getString(edu.playground.djivln.R.string.point_cloud_top)) { viewer.setPreset(V86PointCloudView.ViewPreset.TOP) }
        action(getString(edu.playground.djivln.R.string.point_cloud_front)) { viewer.setPreset(V86PointCloudView.ViewPreset.FRONT) }
        riskButton = action(getString(edu.playground.djivln.R.string.point_cloud_risk_on)) { }
        riskButton.setOnClickListener {
            candidatesVisible = !candidatesVisible
            viewer.setCandidatesVisible(candidatesVisible)
            riskButton.text = getString(if (candidatesVisible) edu.playground.djivln.R.string.point_cloud_risk_on else edu.playground.djivln.R.string.point_cloud_risk_off)
        }
        action(getString(edu.playground.djivln.R.string.action_close)) { finish() }
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(4),
            Gravity.BOTTOM,
        ))
        setContentView(root)
        // Xiaomi Android 16 throws from PhoneWindow.getInsetsController() before
        // the decor view exists. Hide bars only after setContentView attached it.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.hide(WindowInsets.Type.statusBars())
        }
        loadPointCloud()
    }

    private fun loadPointCloud() {
        val ply = internalFile(intent.getStringExtra(EXTRA_PLY_PATH))
        val candidatesFile = internalFile(intent.getStringExtra(EXTRA_CANDIDATES_PATH), required = false)
        if (ply == null || !ply.isFile) {
            status.text = getString(edu.playground.djivln.R.string.point_cloud_missing)
            progress.visibility = View.GONE
            return
        }
        worker.execute {
            val result = runCatching {
                val raw = candidatesFile?.takeIf(File::isFile)?.readText()
                val testOnly = raw?.let { JSONObject(it).optBoolean("test_only", false) } ?: false
                val candidates = if (testOnly) emptyList() else raw?.let(::decodeV86Candidates).orEmpty()
                Triple(V86PlyDecoder.decode(ply, candidates, MAX_MOBILE_POINTS, this@V86PointCloudActivity), candidates.size, testOnly)
            }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onFailure { status.text = getString(edu.playground.djivln.R.string.point_cloud_parse_failed, it.message) }
                result.onSuccess { (cloud, candidateCount, testOnly) ->
                    viewer.setPointCloud(cloud)
                    riskButton.visibility = if (testOnly) View.GONE else View.VISIBLE
                    if (testOnly) viewer.setCandidatesVisible(false)
                    status.text = (if (testOnly) getString(edu.playground.djivln.R.string.v86_relative_test_warning) + "\n" else "") + getString(edu.playground.djivln.R.string.point_cloud_status,
                        ply.name, formatBytes(ply.length()), cloud.size, candidateCount)
                }
            }
        }
    }

    private fun internalFile(path: String?, required: Boolean = true): File? {
        if (path.isNullOrBlank()) return null.also {
            if (required) status.text = getString(edu.playground.djivln.R.string.point_cloud_path_missing)
        }
        val file = File(path).canonicalFile
        val root = filesDir.canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PLY_PATH = "v86_ply_path"
        const val EXTRA_CANDIDATES_PATH = "v86_candidates_path"
        private const val MAX_MOBILE_POINTS = 40_000

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
