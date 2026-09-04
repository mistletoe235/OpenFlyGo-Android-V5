package edu.playground.djivln.ui

data class AppChromeVisibility(
    val topStatusVisible: Boolean,
    val vlnVisible: Boolean,
)

/** Keeps app-level chrome off map-first surfaces where it would obscure map controls. */
object AppChromePolicy {
    fun resolve(
        mapFullscreen: Boolean,
        surveyVisible: Boolean,
        vlnRequested: Boolean,
    ): AppChromeVisibility {
        val mapFirstSurface = mapFullscreen || surveyVisible
        return AppChromeVisibility(
            topStatusVisible = !mapFirstSurface,
            vlnVisible = !mapFirstSurface && vlnRequested,
        )
    }
}
