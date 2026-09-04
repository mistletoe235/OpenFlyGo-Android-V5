package edu.playground.djivln

import android.content.Context
import android.os.Handler
import android.os.Looper
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import java.util.concurrent.CopyOnWriteArraySet

object DjiSdkBootstrap {
    data class State(
        val initialized: Boolean = false,
        val managerInitialized: Boolean = false,
        val registered: Boolean = false,
        val connected: Boolean = false,
        val productId: Int? = null,
        val initEvent: String = "not_started",
        val initProgress: Int = 0,
        val lastError: String? = null,
        val log: List<String> = emptyList()
    )

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var state = State()
    private var initStarted = false
    // Accessed only on the main thread. MSDK can report network availability and
    // INITIALIZE_COMPLETE close together; coalesce both triggers into one registration.
    private var initComplete = false
    private var registrationInFlight = false

    @Synchronized
    fun init(context: Context) {
        if (initStarted) {
            onMain { publish(state) }
            return
        }
        initStarted = true
        update("SDK init requested") { it.copy(initialized = true) }

        runCatching { SDKManager.getInstance() }.onFailure { error ->
            markUnavailable(error)
            return
        }.getOrThrow().init(context.applicationContext, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                onMain {
                    registrationInFlight = false
                    updateNow("SDK register success") {
                        it.copy(registered = true, lastError = null)
                    }
                }
            }

            override fun onRegisterFailure(error: IDJIError) {
                onMain {
                    registrationInFlight = false
                    updateNow("SDK register failure: $error") {
                        it.copy(registered = false, lastError = error.toString())
                    }
                }
            }

            override fun onProductDisconnect(productId: Int) {
                update("Product disconnected: $productId") {
                    it.copy(connected = false, productId = productId)
                }
            }

            override fun onProductConnect(productId: Int) {
                update("Product connected: $productId") {
                    it.copy(connected = true, productId = productId)
                }
            }

            override fun onProductChanged(productId: Int) {
                update("Product changed: $productId") { it.copy(productId = productId) }
            }

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                onMain {
                    updateNow("SDK init process: $event / $totalProcess") {
                        it.copy(initEvent = event.name, initProgress = totalProcess)
                    }
                    if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                        initComplete = true
                        requestRegistrationNow("init complete")
                    }
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                update("Database download: $current / $total") { it }
            }
        })
        update("SDK manager initialized") { it.copy(managerInitialized = true) }

        runCatching {
            DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
                onMain {
                    updateNow("Network available: $isAvailable") { it }
                    if (isAvailable && initComplete) {
                        requestRegistrationNow("network available")
                    }
                }
            }
        }.onFailure(::markUnavailable)
    }

    fun markUnavailable(error: Throwable) {
        val detail = error.message ?: error.javaClass.simpleName
        update("SDK unavailable: $detail") {
            it.copy(registered = false, connected = false, lastError = detail)
        }
    }

    fun registerApp() {
        onMain { requestRegistrationNow("explicit request") }
    }

    private fun requestRegistrationNow(reason: String) {
        if (!initComplete) {
            updateNow("SDK register deferred ($reason): init incomplete") { it }
            return
        }

        val manager = runCatching { SDKManager.getInstance() }.getOrElse { error ->
            markUnavailable(error)
            return
        }
        if (manager.isRegistered) {
            registrationInFlight = false
            updateNow("SDK register skipped ($reason): already registered") {
                it.copy(registered = true, lastError = null)
            }
            return
        }
        if (registrationInFlight) {
            updateNow("SDK register coalesced ($reason): request in flight") { it }
            return
        }

        registrationInFlight = true
        updateNow("SDK register requested ($reason)") { it }
        runCatching { manager.registerApp() }
            .onFailure { error ->
                registrationInFlight = false
                updateNow("SDK register call failed: ${error.message ?: error.javaClass.simpleName}") {
                    it.copy(registered = false, lastError = error.message ?: error.javaClass.simpleName)
                }
            }
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        onMain { listener(state) }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun snapshot(): State = state

    private fun update(message: String, reducer: (State) -> State) {
        onMain { updateNow(message, reducer) }
    }

    private fun updateNow(message: String, reducer: (State) -> State) {
        val nextLog = (state.log + message).takeLast(30)
        state = reducer(state).copy(log = nextLog)
        publish(state)
    }

    private fun publish(next: State) {
        listeners.forEach { it(next) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
