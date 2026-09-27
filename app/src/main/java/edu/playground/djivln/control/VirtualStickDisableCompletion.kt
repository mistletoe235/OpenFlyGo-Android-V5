package edu.playground.djivln.control

class VirtualStickDisableCompletion {
    class Token internal constructor(val generation: Long)

    private var generation = 0L
    private var active: Token? = null
    private var commandCompleted = false
    private var disabledObserved = false

    val commandPending get() = active != null && !commandCompleted
    val ready get() = active != null && commandCompleted && disabledObserved
    val token get() = active

    fun begin(): Token {
        check(!commandPending) { "Virtual Stick disable command is still pending" }
        commandCompleted = false
        disabledObserved = false
        return Token(++generation).also { active = it }
    }

    fun observeEnabled(enabled: Boolean) {
        if (active != null) disabledObserved = !enabled
    }

    fun completeCommand(token: Token): Boolean {
        if (active !== token) return false
        commandCompleted = true
        return true
    }

    fun clear() {
        active = null
        commandCompleted = false
        disabledObserved = false
    }
}
