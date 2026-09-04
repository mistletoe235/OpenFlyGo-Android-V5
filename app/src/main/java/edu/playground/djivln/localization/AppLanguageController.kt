package edu.playground.djivln.localization

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import edu.playground.djivln.R
import java.util.Locale

/** Keeps the in-app picker and Android's per-app locale setting in sync. */
object AppLanguageController {
    private const val PREFERENCES = "openfly_language"
    private const val KEY_SELECTION_COMPLETED = "selection_completed"
    private const val TAG_ZH_HANS = "zh-Hans"
    private const val TAG_ENGLISH = "en"

    fun maybeShowFirstLaunch(activity: AppCompatActivity) {
        val completed = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_SELECTION_COMPLETED, false)
        if (!completed) showPicker(activity, firstLaunch = true)
    }

    fun showSettingsPicker(activity: AppCompatActivity, canChangeNow: Boolean) {
        if (!canChangeNow) {
            Toast.makeText(activity, R.string.language_change_blocked, Toast.LENGTH_LONG).show()
            return
        }
        showPicker(activity, firstLaunch = false)
    }

    private fun showPicker(activity: AppCompatActivity, firstLaunch: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val labels = arrayOf(
            activity.getString(R.string.language_simplified_chinese),
            activity.getString(R.string.language_english),
        )
        var selected = if (currentTag().startsWith("zh")) 0 else 1
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val choices = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
        }
        labels.forEachIndexed { index, label ->
            choices.addView(RadioButton(activity).apply {
                id = View.generateViewId()
                text = label
                isChecked = index == selected
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnCheckedChangeListener { _, checked -> if (checked) selected = index }
            })
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(8))
            addView(TextView(activity).apply {
                setText(R.string.language_change_later)
                setPadding(0, 0, 0, dp(8))
            })
            addView(choices)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.choose_language)
            .setView(content)
            .setPositiveButton(R.string.action_continue, null)
            .apply { if (!firstLaunch) setNegativeButton(R.string.action_cancel, null) }
            .setCancelable(!firstLaunch)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tag = if (selected == 0) TAG_ZH_HANS else TAG_ENGLISH
                activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_SELECTION_COMPLETED, true)
                    .apply()
                dialog.dismiss()
                if (!currentTag().equals(tag, ignoreCase = true)) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            }
        }
        dialog.show()
    }

    private fun currentTag(): String {
        AppCompatDelegate.getApplicationLocales()[0]?.let { return it.toLanguageTag() }
        return if (Locale.getDefault().language.startsWith("zh")) TAG_ZH_HANS else TAG_ENGLISH
    }
}
