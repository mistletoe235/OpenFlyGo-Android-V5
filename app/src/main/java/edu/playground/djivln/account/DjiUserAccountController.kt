package edu.playground.djivln.account

import androidx.fragment.app.FragmentActivity
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.account.LoginInfo
import dji.v5.manager.account.LoginInfoUpdateListener
import dji.v5.manager.account.LoginState
import dji.v5.manager.account.UserAccountManager
import dji.v5.manager.interfaces.IUserAccountManager
import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

enum class DjiAccountState {
    LOGGED_IN,
    NOT_LOGGED_IN,
    TOKEN_OUT_OF_DATE,
    UNKNOWN,
}

data class DjiAccountSnapshot(
    val state: DjiAccountState = DjiAccountState.UNKNOWN,
    val account: String? = null,
    val lastError: String? = null,
) {
    val loggedIn: Boolean get() = state == DjiAccountState.LOGGED_IN
    val label: UiText get() {
        val base = when (state) {
            DjiAccountState.LOGGED_IN -> UiText.resource(
                R.string.dji_account_logged_in,
                account?.let { UiText.resource(R.string.dji_account_suffix, it) } ?: UiText.external(""),
            )
            DjiAccountState.NOT_LOGGED_IN -> UiText.resource(R.string.dji_account_not_logged_in)
            DjiAccountState.TOKEN_OUT_OF_DATE -> UiText.resource(R.string.dji_account_expired)
            DjiAccountState.UNKNOWN -> UiText.resource(R.string.dji_account_checking)
        }
        return lastError?.let { UiText.resource(R.string.dji_account_recent_error, base, it) } ?: base
    }
}

object DjiAccountFlightPolicy {
    fun shouldPromptAtStartup(snapshot: DjiAccountSnapshot): Boolean = when (snapshot.state) {
        DjiAccountState.NOT_LOGGED_IN, DjiAccountState.TOKEN_OUT_OF_DATE -> true
        DjiAccountState.LOGGED_IN, DjiAccountState.UNKNOWN -> false
    }

    fun blockingReason(snapshot: DjiAccountSnapshot, requiresAccount: Boolean = true): UiText? {
        if (!requiresAccount) return null
        return when (snapshot.state) {
        DjiAccountState.LOGGED_IN -> null
        DjiAccountState.NOT_LOGGED_IN -> UiText.resource(R.string.dji_account_required_not_logged_in)
        DjiAccountState.TOKEN_OUT_OF_DATE -> UiText.resource(R.string.dji_account_required_expired)
        DjiAccountState.UNKNOWN -> UiText.resource(R.string.dji_account_required_unknown)
        }
    }
}

class DjiUserAccountController(
    private val activity: FragmentActivity,
    private val recordEvent: (String, Map<String, Any?>) -> Unit,
    private val onChanged: (DjiAccountSnapshot) -> Unit,
) : AutoCloseable {
    private val manager: IUserAccountManager = UserAccountManager.getInstance()
    @Volatile private var current = DjiAccountSnapshot()
    private val listener = LoginInfoUpdateListener(::update)

    init {
        manager.init()
        manager.addLoginInfoUpdateListener(listener)
        update(manager.loginInfo)
    }

    fun snapshot(): DjiAccountSnapshot = current

    fun login() {
        recordEvent("dji_account_login_requested", emptyMap())
        manager.logInDJIUserAccount(activity, false, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                update(manager.loginInfo)
                recordEvent("dji_account_login_result", mapOf("result" to "SUCCESS"))
            }

            override fun onFailure(error: IDJIError) {
                val detail = listOfNotNull(
                    error.description()?.takeIf(String::isNotBlank),
                    error.hint()?.takeIf(String::isNotBlank),
                    error.errorCode()?.takeIf(String::isNotBlank),
                ).distinct().joinToString(" · ").ifBlank { error.toString() }
                current = current.copy(lastError = detail)
                recordEvent("dji_account_login_result", mapOf("result" to "FAIL", "error" to detail))
                activity.runOnUiThread { onChanged(current) }
            }
        })
    }

    fun refresh() = update(manager.loginInfo)

    private fun update(info: LoginInfo?) {
        val next = DjiAccountSnapshot(
            state = when (info?.loginState) {
                LoginState.LOGGED_IN -> DjiAccountState.LOGGED_IN
                LoginState.NOT_LOGGED_IN -> DjiAccountState.NOT_LOGGED_IN
                LoginState.TOKEN_OUT_OF_DATE -> DjiAccountState.TOKEN_OUT_OF_DATE
                else -> DjiAccountState.UNKNOWN
            },
            account = info?.account?.takeIf(String::isNotBlank)?.let(::maskAccount),
        )
        if (next == current) return
        current = next
        recordEvent("dji_account_state", mapOf("state" to next.state.name, "account" to next.account))
        activity.runOnUiThread { onChanged(next) }
    }

    override fun close() {
        manager.removeLoginInfoUpdateListener(listener)
    }

    private fun maskAccount(value: String): String = when {
        value.length <= 3 -> "***"
        value.contains('@') -> value.take(2) + "***" + value.substring(value.indexOf('@'))
        else -> value.take(3) + "***" + value.takeLast(2)
    }
}
