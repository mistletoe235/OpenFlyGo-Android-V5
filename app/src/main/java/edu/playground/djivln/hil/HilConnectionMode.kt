package edu.playground.djivln.hil

enum class HilConnectionMode {
    /** Android and UE are clients of an existing router/AP; UE has a configured address. */
    LAN,

    /** Android provides the system hotspot; UE joins it and is discovered by UDP broadcast. */
    HOTSPOT,
}
