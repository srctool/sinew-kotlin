package com.srctool.sinew.docs

import com.srctool.sinew.exception.processResult
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.presentation.AppService
import com.srctool.sinew.presentation.AppServiceHost
import com.srctool.sinew.presentation.ServiceLifetime
import com.srctool.sinew.viewmodel.StateEffectViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// region counter
data class CounterState(val count: Int = 0)

/** A one-time reaction: never state, so a rebuild can't repeat it. */
data object ReachedTen

class CounterViewModel : StateEffectViewModel<CounterState, ReachedTen>(CounterState()) {
    fun increment() {
        setState { copy(count = count + 1) }
        if (state.value.count == 10) emitEffect(ReachedTen)
    }
}
// endregion

data class Purchase(val id: String)

/** What the store gives the app: purchases made while it wasn't running, and ones arriving now. */
interface PurchaseStore {
    suspend fun unacknowledged(): List<Purchase>
    val arrivals: Flow<Purchase>
}

/** The use case a page could call too: idempotent, because the store delivers the same purchase again. */
fun interface AcknowledgePurchase {
    suspend operator fun invoke(purchase: Purchase)
}

// region app-service
/** Work with the app's lifetime and no UI: purchases arrive from the store at any time, with or without a screen. */
class BillingService(
    private val store: PurchaseStore,
    private val acknowledge: AcknowledgePurchase,
) : AppService(ServiceLifetime.App) {

    /** Catches up first, before [listen] subscribes. A bug here is reported as `BIL-SV-INIT-GEN`. */
    override suspend fun init() {
        processResult("BIL", "INIT", ExceptionLayer.Service) {
            store.unacknowledged().forEach { acknowledge(it) }
        }
    }

    override fun listen(scope: CoroutineScope) {
        scope.launch {
            store.arrivals.collect { purchase ->
                processResult("BIL", "LISTEN", ExceptionLayer.Service) { acknowledge(purchase) }
            }
        }
    }
}
// endregion

// region app-service-host
/** Built once at app start, with the scope that outlives every screen. */
fun appServices(store: PurchaseStore, acknowledge: AcknowledgePurchase) = AppServiceHost(
    appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    services = listOf(BillingService(store, acknowledge)),
)

/** App services start at launch, session services at sign-in and stop at sign-out. */
class AppLifecycle(private val services: AppServiceHost) {
    fun launched() = services.startApp()
    fun signedIn() = services.startSession()
    suspend fun signedOut() = services.stopSession()
}
// endregion
