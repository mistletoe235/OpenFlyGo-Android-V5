package edu.playground.djivln.domain.wayline

object WaylineMissionName {
    fun normalizeOrNull(value: String?): String? {
        val fileName = value
            ?.trim()
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: return null
        return if (fileName.endsWith(KMZ_SUFFIX, ignoreCase = true)) {
            fileName.dropLast(KMZ_SUFFIX.length)
        } else {
            fileName
        }.takeIf(String::isNotBlank)
    }

    fun normalize(value: String): String {
        return requireNotNull(normalizeOrNull(value)) { "Wayline mission name cannot be empty" }
    }

    fun matches(left: String?, right: String?): Boolean =
        normalizeOrNull(left)?.let { it == normalizeOrNull(right) } == true

    private const val KMZ_SUFFIX = ".kmz"
}
