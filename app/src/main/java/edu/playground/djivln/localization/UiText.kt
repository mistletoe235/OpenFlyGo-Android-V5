package edu.playground.djivln.localization

import android.content.Context
import androidx.annotation.StringRes

/** A resource-backed message that remains locale-independent in domain state. */
data class UiText(
    @StringRes val resourceId: Int? = null,
    val arguments: List<Any?> = emptyList(),
    val rawText: String? = null,
) {
    init {
        require((resourceId != null) xor (rawText != null))
    }

    companion object {
        fun resource(@StringRes resourceId: Int, vararg arguments: Any?): UiText =
            UiText(resourceId = resourceId, arguments = arguments.toList())

        /** For external SDK/server detail that is not owned by the app. */
        fun external(value: String): UiText = UiText(rawText = value)
    }
}

fun Context.resolve(uiText: UiText): String = uiText.rawText ?: getString(
    requireNotNull(uiText.resourceId),
    *uiText.arguments.map { if (it is UiText) resolve(it) else it }.toTypedArray(),
)
