package edu.playground.djivln.adapter.dji

internal object DjiTelemetryBatteryPolicy {
    fun aircraftPercent(aggregate: Int?, leftOrMain: Int?, right: Int?): Int? =
        aggregate ?: listOfNotNull(leftOrMain, right).minOrNull()

    fun remoteControllerAvailablePercent(internal: Int?, external: Int?): Int? =
        listOfNotNull(internal, external).maxOrNull()

    /**
     * MSDK5 can interleave a valid RC battery sample with a transient null or
     * disabled sample while the product remains connected. RC charge changes
     * slowly, so keep the last valid value until the connection lifecycle
     * explicitly clears it.
     */
    fun retainConnectedRcPercent(previous: Int?, reported: Int?, enabled: Boolean?): Int? =
        reported?.takeIf { enabled != false && it in 0..100 } ?: previous
}
