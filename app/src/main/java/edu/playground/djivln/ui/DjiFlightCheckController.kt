package edu.playground.djivln.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import edu.playground.djivln.R
import dji.v5.manager.diagnostic.DJIDeviceHealthInfo
import dji.v5.manager.diagnostic.DJIDeviceHealthInfoChangeListener
import dji.v5.manager.diagnostic.DJIDeviceStatus
import dji.v5.manager.diagnostic.DJIDeviceStatusChangeListener
import dji.v5.manager.diagnostic.DeviceHealthManager
import dji.v5.manager.diagnostic.DeviceStatusManager
import dji.v5.manager.diagnostic.WarningLevel

class DjiFlightCheckController(
    private val activity: Activity,
    private val summaryView: TextView,
    private val aircraftConnected: () -> Boolean,
    private val recordEvent: (String, Map<String, Any?>) -> Unit,
) {
    private var healthItems: List<FlightCheckItem> = emptyList()
    private var systemStatus: DJIDeviceStatus = DJIDeviceStatus.UNKNOWN
    private var started = false
    private var lastLoggedSignature: String? = null
    private var lastConnectionState: Boolean? = null

    private val healthListener = DJIDeviceHealthInfoChangeListener { infos ->
        activity.runOnUiThread {
            healthItems = infos.map { it.toFlightCheckItem() }
                .filterNot { it.level == WarningLevel.NORMAL }
                .distinctBy { "${it.code}:${it.title}:${it.description}" }
                .sortedWith(compareByDescending<FlightCheckItem> { priority(it.level) }.thenBy { it.code })
            render()
        }
    }

    private val statusListener = DJIDeviceStatusChangeListener { _, current ->
        activity.runOnUiThread {
            systemStatus = current
            render()
        }
    }

    init {
        summaryView.setOnClickListener { showDetails() }
        render()
    }

    fun start() {
        if (started) return
        started = true
        DeviceHealthManager.getInstance().init()
        DeviceStatusManager.getInstance().init()
        DeviceHealthManager.getInstance().addDJIDeviceHealthInfoChangeListener(healthListener)
        DeviceStatusManager.getInstance().addDJIDeviceStatusChangeListener(statusListener)
        healthListener.onDeviceHealthInfoUpdate(DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos)
        systemStatus = DeviceStatusManager.getInstance().currentDJIDeviceStatus
        render()
    }

    fun stop() {
        if (!started) return
        started = false
        DeviceHealthManager.getInstance().removeDJIDeviceHealthInfoChangeListener(healthListener)
        DeviceStatusManager.getInstance().removeDJIDeviceStatusChangeListener(statusListener)
    }

    fun refreshConnectionState() {
        val connected = aircraftConnected()
        if (connected == lastConnectionState) return
        lastConnectionState = connected
        render()
    }

    private fun render() {
        val connected = aircraftConnected()
        val seriousCount = healthItems.count { it.level == WarningLevel.SERIOUS_WARNING }
        val warningCount = healthItems.count { it.level == WarningLevel.WARNING }
        val cautionCount = healthItems.count { it.level == WarningLevel.CAUTION || it.level == WarningLevel.NOTICE }
        val displayedSystemStatus = displayableSystemStatus(connected)
        val overallAbnormal = displayedSystemStatus?.warningLevel() != null &&
            displayedSystemStatus.warningLevel() != WarningLevel.NORMAL
        val topItem = healthItems.firstOrNull()

        summaryView.text = when {
            !connected -> activity.getString(R.string.flight_check_disconnected)
            healthItems.isEmpty() && !overallAbnormal -> activity.getString(R.string.flight_check_normal)
            else -> buildString {
                append(activity.getString(R.string.flight_check_prefix)).append("  ")
                if (seriousCount > 0) append(activity.getString(R.string.flight_check_serious_count, seriousCount)).append(' ')
                if (warningCount > 0) append(activity.getString(R.string.flight_check_warning_count, warningCount)).append(' ')
                if (cautionCount > 0) append(activity.getString(R.string.flight_check_notice_count, cautionCount)).append(' ')
                if (healthItems.isEmpty()) append(displayedSystemStatus?.description().orEmpty().ifBlank { activity.getString(R.string.status_abnormal) })
                topItem?.let { append("· ${it.code}") }
            }.trim()
        }
        summaryView.setTextColor(
            when {
                !connected -> Color.rgb(190, 199, 210)
                seriousCount > 0 || warningCount > 0 -> Color.WHITE
                cautionCount > 0 || overallAbnormal -> Color.rgb(255, 235, 180)
                else -> Color.rgb(190, 255, 205)
            },
        )
        summaryView.background = roundedBackground(
            when {
                !connected -> Color.argb(52, 255, 255, 255)
                seriousCount > 0 || warningCount > 0 -> Color.argb(215, 255, 69, 58)
                cautionCount > 0 || overallAbnormal -> Color.argb(205, 255, 159, 10)
                else -> Color.argb(190, 48, 209, 88)
            },
        )

        val signature = buildString {
            append(connected).append('|').append(systemStatus.name)
            healthItems.forEach { append('|').append(it.level.name).append(':').append(it.code) }
        }
        if (signature != lastLoggedSignature) {
            lastLoggedSignature = signature
            recordEvent(
                "dji_flight_check_changed",
                mapOf(
                    "connected" to connected,
                    "system_status" to systemStatus.name,
                    "system_status_code" to systemStatus.statusCode(),
                    "system_description" to systemStatus.description(),
                    "system_warning_level" to systemStatus.warningLevel().name,
                    "serious_count" to seriousCount,
                    "warning_count" to warningCount,
                    "caution_count" to cautionCount,
                    "items" to healthItems.map { item ->
                        mapOf(
                            "code" to item.code,
                            "level" to item.level.name,
                            "title" to item.title,
                            "description" to item.description,
                        )
                    },
                ),
            )
        }
    }

    private fun showDetails() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val connected = aircraftConnected()
        val displayedSystemStatus = displayableSystemStatus(connected)
        if (displayedSystemStatus != null && (healthItems.isEmpty() || displayedSystemStatus.warningLevel() != WarningLevel.NORMAL)) {
            container.addView(
                detailText(
                    activity.getString(R.string.system_status),
                    displayedSystemStatus.description(),
                    displayedSystemStatus.statusCode(),
                    displayedSystemStatus.warningLevel(),
                    true,
                ),
            )
        }

        if (!connected) {
            container.addView(detailText(activity.getString(R.string.aircraft_disconnected), activity.getString(R.string.flight_check_connect_hint), "--", WarningLevel.UNKNOWN, displayedSystemStatus == null))
        } else if (healthItems.isEmpty()) {
            container.addView(detailText(activity.getString(R.string.flight_check_no_issue), activity.getString(R.string.flight_check_no_warning), "NORMAL", WarningLevel.NORMAL, displayedSystemStatus == null))
        } else {
            healthItems.forEachIndexed { index, item ->
                container.addView(detailText(item.title, item.description, item.code, item.level, displayedSystemStatus == null && index == 0))
            }
        }

        val scrollView = ScrollView(activity).apply { addView(container) }
        AlertDialog.Builder(activity)
            .setTitle(edu.playground.djivln.R.string.dji_flight_check_title)
            .setView(scrollView)
            .setPositiveButton(edu.playground.djivln.R.string.action_close, null)
            .show()
        recordEvent("dji_flight_check_opened", mapOf("item_count" to healthItems.size, "system_status" to systemStatus.name))
    }

    private fun detailText(
        title: String,
        description: String,
        code: String,
        level: WarningLevel,
        first: Boolean,
    ): TextView = TextView(activity).apply {
        text = buildString {
            append(title.ifBlank { activity.getString(R.string.dji_device_status) })
            append("  [${levelLabel(level)}]")
            appendLine()
            append(description.ifBlank { activity.getString(R.string.no_detail_available) })
            appendLine()
            append(activity.getString(R.string.error_code_value, code.ifBlank { "--" }))
        }
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextIsSelectable(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBackground(levelColor(level))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (!first) topMargin = dp(8)
        }
    }

    private fun DJIDeviceHealthInfo.toFlightCheckItem() = FlightCheckItem(
        code = informationCode().orEmpty(),
        title = title().orEmpty().ifBlank { activity.getString(R.string.dji_device_status) },
        description = description().orEmpty(),
        level = warningLevel() ?: WarningLevel.UNKNOWN,
    )

    private fun displayableSystemStatus(connected: Boolean): DJIDeviceStatus? {
        if (connected && systemStatus in setOf(DJIDeviceStatus.REMOTE_DISCONNECT, DJIDeviceStatus.AIRCRAFT_DISCONNECT)) {
            return null
        }
        return systemStatus.takeUnless { it == DJIDeviceStatus.UNKNOWN }
    }

    private fun roundedBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(5).toFloat()
        setColor(color)
        setStroke(dp(1), Color.argb(100, 255, 255, 255))
    }

    private fun levelColor(level: WarningLevel): Int = when (level) {
        WarningLevel.SERIOUS_WARNING, WarningLevel.WARNING -> Color.rgb(125, 35, 35)
        WarningLevel.CAUTION, WarningLevel.NOTICE -> Color.rgb(104, 74, 26)
        WarningLevel.NORMAL -> Color.rgb(27, 87, 53)
        WarningLevel.UNKNOWN -> Color.rgb(55, 63, 73)
    }

    private fun levelLabel(level: WarningLevel): String = when (level) {
        WarningLevel.SERIOUS_WARNING -> activity.getString(R.string.warning_level_serious)
        WarningLevel.WARNING -> activity.getString(R.string.warning_level_warning)
        WarningLevel.CAUTION -> activity.getString(R.string.warning_level_caution)
        WarningLevel.NOTICE -> activity.getString(R.string.warning_level_notice)
        WarningLevel.NORMAL -> activity.getString(R.string.warning_level_normal)
        WarningLevel.UNKNOWN -> activity.getString(R.string.warning_level_unknown)
    }

    private fun priority(level: WarningLevel): Int = when (level) {
        WarningLevel.SERIOUS_WARNING -> 5
        WarningLevel.WARNING -> 4
        WarningLevel.CAUTION -> 3
        WarningLevel.NOTICE -> 2
        WarningLevel.NORMAL -> 1
        WarningLevel.UNKNOWN -> 0
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private data class FlightCheckItem(
        val code: String,
        val title: String,
        val description: String,
        val level: WarningLevel,
    )
}
