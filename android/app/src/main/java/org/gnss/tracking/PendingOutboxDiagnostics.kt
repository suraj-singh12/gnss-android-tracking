package org.gnss.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

// A service-owned observer. Native callbacks and maintenance only read volatile state.
internal class PendingOutboxDiagnostics {
    data class State(val value: Int? = null, val unavailable: Boolean? = null)

    @Volatile
    var snapshot = State()
        private set

    val value
        get() = snapshot.value

    val unavailable
        get() = snapshot.unavailable

    suspend fun observe(onChange: suspend () -> Unit = {}, counts: () -> Flow<Int>) {
        while (currentCoroutineContext().isActive) {
            try {
                counts().collect { count ->
                    val changed = value != count || unavailable != false
                    snapshot = State(count, false)
                    if (changed) runCatching { onChange() }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                snapshot = State(null, true)
                runCatching { onChange() }
                // This child never propagates database/diagnostic failure to tracking.
                delay(5000)
            }
        }
    }
}
