package org.gnss.tracking

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow

data class Operational(
    val tracking: Boolean = false,
    val gnss: String = "unknown",
    // UI metadata from the last real observation, even after it becomes stale.
    val accuracy: Double? = null,
    val ageMillis: Long? = null,
    val health: Health = Health(),
    val link: String = "Awaiting Command",
    val error: String? = null,
    val warning: String? = null,
    val starting: Boolean = false,
)

class TrackingApp : Application() {
    val repository by lazy { Repository(TrackingDatabase.open(this)) }
    val operational = MutableStateFlow(Operational())
    val diagnostics = MutableStateFlow<GnssDiagnostic?>(null)
}
