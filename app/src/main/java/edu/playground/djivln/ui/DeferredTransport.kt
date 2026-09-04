package edu.playground.djivln.ui

enum class DeferredTransport { LOCAL, USB, ETHERNET }

object DeferredEndpoint {
    const val LOCAL_BASE = "disabled://local"
    const val USB_BASE = "disabled://usb"
    const val DEFAULT_ETHERNET_BASE = "disabled://ethernet"
    const val LEGACY_CONFLICTING_ETHERNET_BASE = DEFAULT_ETHERNET_BASE
    fun baseUrl(transport: DeferredTransport, ethernetInput: String): String = "disabled://${transport.name.lowercase()}"
    fun inferenceUrl(transport: DeferredTransport, ethernetInput: String): String = baseUrl(transport, ethernetInput)
    fun normalizeEthernetBase(input: String): String = DEFAULT_ETHERNET_BASE
}
