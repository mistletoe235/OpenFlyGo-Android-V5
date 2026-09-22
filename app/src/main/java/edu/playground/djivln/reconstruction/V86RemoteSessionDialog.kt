package edu.playground.djivln.reconstruction

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import edu.playground.djivln.R
import java.io.File
import java.util.concurrent.Executors

class V86RemoteSessionDialog(
    private val activity: Activity,
    private val importer: MissionImporter,
) : AutoCloseable {
    fun interface MissionImporter { fun importMission(raw: String) }

    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = activity.getSharedPreferences("v86_remote_browser", Context.MODE_PRIVATE)
    private val tokens = V86SecureTokenStore(activity, "v86_remote_browser_secure")
    private var client: V86RemoteSessionClient? = null
    private var dialog: AlertDialog? = null
    private var generation = 0

    fun show() {
        dialog?.dismiss()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (18 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun field(hintId: Int, value: String, secret: Boolean = false) = EditText(activity).apply {
            hint = activity.getString(hintId)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
            content.addView(this)
        }
        val endpoint = field(R.string.v86_remote_endpoint,
            preferences.getString("endpoint", V86HttpClient.DEFAULT_ENDPOINT).orEmpty())
        val token = field(R.string.v86_remote_token, tokens.load().orEmpty(), true)
        val session = field(R.string.v86_remote_session, preferences.getString("session", "").orEmpty())
        val status = TextView(activity).apply {
            setText(R.string.v86_remote_description)
            content.addView(this)
        }
        fun button(title: Int) = Button(activity).apply { setText(title); content.addView(this) }
        val refresh = button(R.string.v86_remote_refresh)
        val cloud = button(R.string.v86_remote_cloud)
        val mission = button(R.string.v86_remote_mission)
        var result: V86Result? = null
        fun controls(busy: Boolean) {
            endpoint.isEnabled = !busy
            token.isEnabled = !busy
            session.isEnabled = !busy
            refresh.isEnabled = !busy
            cloud.isEnabled = !busy && result?.pointCloudUrl != null
            mission.isEnabled = !busy && result?.missionUrl != null &&
                result?.relativeHeightTest == false
        }
        fun <T> perform(action: () -> T, complete: (T) -> Unit) {
            val current = generation
            controls(true)
            status.setText(R.string.v86_remote_loading)
            worker.execute {
                val response = runCatching(action)
                activity.runOnUiThread {
                    if (generation != current || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                    response.onSuccess { value ->
                        runCatching { complete(value) }.onFailure {
                            status.text = activity.getString(R.string.v86_remote_failed, it.message.orEmpty())
                        }
                    }.onFailure {
                        status.text = activity.getString(R.string.v86_remote_failed, it.message.orEmpty())
                    }
                    controls(false)
                }
            }
        }
        refresh.setOnClickListener {
            client?.close()
            result = null
            val connection = runCatching {
                V86RemoteSessionClient(endpoint.text.toString().trim(), session.text.toString().trim(), token.text.toString())
            }.getOrElse {
                status.text = activity.getString(R.string.v86_remote_failed, it.message.orEmpty())
                controls(false)
                return@setOnClickListener
            }
            client = connection
            perform({ connection.result() }) { value ->
                tokens.save(token.text.toString())
                preferences.edit().putString("endpoint", connection.endpoint)
                    .putString("session", connection.sessionId).apply()
                result = value
                status.text = "${value.sessionId} · ${value.phase}\n${value.message}\n" +
                    listOfNotNull(value.error, value.missionError).joinToString("\n") +
                    if (value.relativeHeightTest || !value.safeToExecute) "\n" + activity.getString(R.string.v86_remote_unapproved) else ""
            }
        }
        cloud.setOnClickListener {
            val connection = client ?: return@setOnClickListener
            val value = result ?: return@setOnClickListener
            perform({ connection.pointCloud(value, File(activity.filesDir, "v86-remote-view/cloud.ply")) }) { file ->
                activity.startActivity(Intent(activity, V86PointCloudActivity::class.java)
                    .putExtra(V86PointCloudActivity.EXTRA_PLY_PATH, file.absolutePath))
                status.setText(R.string.v86_remote_cloud_ready)
            }
        }
        mission.setOnClickListener {
            val connection = client ?: return@setOnClickListener
            val value = result ?: return@setOnClickListener
            perform({ connection.mission(value) }) { raw ->
                if (V86MissionPreview.canImport(value, raw)) {
                    importer.importMission(raw)
                    status.setText(R.string.v86_remote_review)
                } else {
                    V86MissionPreviewDialog.show(activity, raw)
                    status.setText(R.string.v86_remote_preview_only)
                }
            }
        }
        fun watch(field: EditText, clearToken: Boolean) {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    generation++
                    client?.close()
                    client = null
                    result = null
                    if (clearToken) token.setText("")
                    status.setText(R.string.v86_remote_description)
                    controls(false)
                }
            })
        }
        watch(endpoint, true)
        watch(session, false)
        watch(token, false)
        controls(false)
        dialog = AlertDialog.Builder(activity).setTitle(R.string.v86_remote_open)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null).create().also { window ->
                window.setOnDismissListener {
                    generation++
                    client?.close()
                    client = null
                    dialog = null
                }
                window.show()
            }
    }

    override fun close() {
        dialog?.dismiss()
        client?.close()
        worker.shutdownNow()
    }
}
