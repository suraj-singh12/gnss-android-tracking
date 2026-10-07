package org.gnss.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class MainActivity : Activity() {
    private val app
        get() = application as TrackingApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var partyId: EditText
    private lateinit var partyName: EditText
    private lateinit var endpoint: EditText
    private lateinit var interval: EditText
    private lateinit var status: TextView
    private var initialized = false
    private var startAfterPermission = false
    private var visible = false
    private val trackingIntent by lazy { Intent(this, TrackingService::class.java) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(24))
            }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val (top, bottom) =
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    bars.top to bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
                }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        fun text(label: String, size: Float = 16f): TextView =
            TextView(this).apply {
                text = label
                textSize = size
                setTextColor(0xff172b34.toInt())
                setPadding(0, dp(12), 0, dp(4))
                content.addView(this)
            }
        fun edit(label: String, numeric: Boolean = false): EditText {
            val heading = text(label)
            return EditText(this).apply {
                id = View.generateViewId()
                heading.labelFor = id
                setSingleLine(true)
                minHeight = dp(48)
                textSize = 16f
                inputType =
                    if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
                    else android.text.InputType.TYPE_CLASS_TEXT
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        fun button(label: String, action: () -> Unit) =
            Button(this).apply {
                text = label
                minHeight = dp(48)
                isAllCaps = false
                setOnClickListener { action() }
                content.addView(this)
            }
        text("GNSS Tracking", 24f)
        status = text("Loading saved settings…")
        text("Party", 20f)
        partyId = edit("Party ID")
        partyName = edit("Party name")
        text("Command and reporting", 20f)
        endpoint = edit("Command base URL (local Wi-Fi)")
        endpoint.hint = "http://192.168.1.10:8080"
        interval = edit("Local reporting interval (seconds, 5–86400 in steps of 5)", true)
        button("Save settings") { save() }
        button("Retry saved messages") {
            AlertDialog.Builder(this)
                .setTitle("Retry pending delivery?")
                .setMessage(
                    "Retry saved messages after correcting a receiver or delivery error. Current Command enrollment and reporting override remain applied."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Retry") { _, _ ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { app.repository.retryDelivery() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status.text = getString(R.string.cannot_start, e.message)
                        }
                    }
                }
                .show()
        }
        button("Enroll with a different Command") {
            AlertDialog.Builder(this)
                .setTitle("Change Command enrollment?")
                .setMessage(
                    "Clear the remote reporting override and bind the next successful reply from this receiver. Saved messages and device identity are retained."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Re-enroll") { _, _ -> save(reenroll = true) }
                .show()
        }
        button("Battery/background settings") {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (e: android.content.ActivityNotFoundException) {
                AlertDialog.Builder(this)
                    .setMessage(
                        "Open Android Settings → Apps → GNSS Tracking → Battery and allow unrestricted background activity."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        text("Tracking", 20f)
        button("Start Tracking") { save(start = true) }
        button("Stop Tracking") {
            AlertDialog.Builder(this)
                .setTitle("Stop tracking on this phone?")
                .setMessage(
                    "Location collection and sending will stop. Saved pending messages remain on this phone until tracking starts again."
                )
                .setNegativeButton("Keep tracking", null)
                .setPositiveButton("Stop Tracking") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            app.repository.edit { it.copy(tracking = false) }
                        }
                        stopService(trackingIntent)
                    }
                }
                .show()
        }
        scope.launch {
            withContext(Dispatchers.IO) { app.repository.state() }
            combine(
                    app.repository.dao.observeState(),
                    app.repository.dao.pendingCount(),
                    app.repository.dao.blockedCount(),
                    app.repository.dao.blockedError(),
                    app.operational,
                ) { state, pending, blocked, blockedError, op ->
                    if (state != null) {
                        if (!initialized) {
                            partyId.setText(state.partyId)
                            partyName.setText(state.partyName)
                            endpoint.setText(state.endpoint)
                            interval.setText(
                                getString(R.string.interval_value, state.localInterval)
                            )
                            initialized = true
                        }
                        val age = op.ageMillis?.let { "${it/1000} s" } ?: "Unavailable"
                        status.text = buildString {
                            appendLine(
                                if (op.tracking) "Tracking active"
                                else "Tracking stopped — start to collect and send"
                            )
                            val gps =
                                if (op.gnss == "unknown" && (op.ageMillis ?: 0) > 30000) "Stale fix"
                                else op.gnss.replace('_', ' ')
                            appendLine(
                                "GPS: $gps • accuracy: ${op.accuracy?.let { "%.1f m".format(it) } ?: "Unavailable"}"
                            )
                            appendLine("Fix age: $age")
                            appendLine("Command: ${state.endpoint.ifEmpty { "Not configured" }}")
                            appendLine("${op.link} • last ACK: ${state.lastAck ?: "None"}")
                            appendLine(
                                "Reporting: local ${state.localInterval} s • effective ${state.config().effective_reporting_interval_s} s"
                            )
                            if (state.overrideSeconds != null)
                                appendLine("Command override active (${state.overrideSeconds} s)")
                            appendLine(
                                "Battery: ${op.health.battery_percent?.let { "$it%" } ?: "Unavailable"} • pending: $pending"
                            )
                            if (blocked > 0)
                                appendLine(
                                    "$blocked saved message(s) need attention: ${blockedError ?: "Delivery blocked"}"
                                )
                            if (state.deliveryPaused)
                                appendLine(
                                    "Sending paused — correct receiver, then Retry saved messages"
                                )
                            op.warning?.let { appendLine("Field setup: $it") }
                            state.configError?.let { appendLine("Configuration error: $it") }
                            (op.error ?: state.operationalError)?.let {
                                appendLine("Attention: $it")
                            }
                        }
                    }
                }
                .collect {}
        }
    }

    private fun save(start: Boolean = false, reenroll: Boolean = false) {
        if (!initialized) return
        val id = partyId.text.toString().trim()
        val name = partyName.text.toString().trim()
        val url = endpoint.text.toString().trim()
        val seconds = interval.text.toString().toIntOrNull()
        scope.launch {
            try {
                require(seconds != null) { "Enter a whole number of seconds" }
                if (start) require(url.isNotEmpty()) { "Configure a Command URL before tracking" }
                withContext(Dispatchers.IO) {
                    app.repository.settings(id, name, url, seconds, reenroll)
                }
                if (start) requestStart()
                else Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Check settings")
                    .setMessage(e.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun requestStart() {
        // Settings/permission work can finish after Home/lock hides this Activity.
        // A location FGS must be created from a visible user action; do not retry
        // automatically on resume or create a background FGS with denied location.
        if (!visible || isFinishing || isDestroyed) {
            app.operational.update {
                it.copy(error = "Settings saved. Tap Start Tracking while the app is visible.")
            }
            return
        }
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            startAfterPermission = true
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                1,
            )
            return
        }
        try {
            startForegroundService(trackingIntent)
        } catch (e: Exception) {
            status.text = getString(R.string.cannot_start, e.message)
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && startAfterPermission) {
            startAfterPermission = false
            if (
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            )
                requestStart()
            else
                AlertDialog.Builder(this)
                    .setTitle("Precise location needed")
                    .setMessage(
                        "Tracking requires precise location. Enable it in Android app permissions and try Start Tracking again."
                    )
                    .setPositiveButton("OK", null)
                    .show()
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
