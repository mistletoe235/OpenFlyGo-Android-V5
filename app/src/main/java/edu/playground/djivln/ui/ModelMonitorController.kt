package edu.playground.djivln.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.FrameLayout
import edu.playground.djivln.R
import edu.playground.djivln.databinding.ViewModelMonitorBinding

class ModelMonitorController(
    activity: Activity,
    container: FrameLayout,
    initialLines: List<String>,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    private val binding = ViewModelMonitorBinding.inflate(LayoutInflater.from(activity), container, false)
    private val lines = ArrayDeque<String>()
    private var follow = true

    init {
        container.addView(binding.root)
        initialLines.forEach(::append)
        binding.followLog.setOnClickListener {
            follow = !follow
            binding.followLog.text = activity.getString(if (follow) R.string.follow_on else R.string.follow_off)
            if (follow) scrollToLatest()
        }
        binding.clearLog.setOnClickListener {
            lines.clear()
            binding.logText.text = ""
            onClear()
        }
        binding.closeLog.setOnClickListener { onClose() }
    }

    fun append(message: String) {
        if (message.isBlank()) return
        lines.addLast(message)
        while (lines.size > MAX_LINES) lines.removeFirst()
        binding.logText.text = lines.joinToString("\n")
        if (follow) scrollToLatest()
    }

    private fun scrollToLatest() {
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private companion object {
        const val MAX_LINES = 160
    }
}
