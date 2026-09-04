package edu.playground.djivln.adapter.dji

/** Pure mapping kept independent of MSDK so it is unit-testable on the JVM. */
object DjiWpmzPayloadPosition {
    private val supportedValues = setOf(
        0, 1, 2, 3, 6,
        20_001, 20_002, 20_003, 20_004, 20_005, 20_006, 20_007,
    )

    fun fromComponentValue(componentIndexValue: Int): Int? =
        componentIndexValue.takeIf(supportedValues::contains)
}
