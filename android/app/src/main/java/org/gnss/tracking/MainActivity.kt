package org.gnss.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
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
    private lateinit var exportStatus: TextView
    private var exporting = false
    private lateinit var sosStatus: TextView
    private lateinit var keyOption: CheckBox
    private val volumePattern = TripleVolumeUp { count ->
        app.recorder.sos(
            DiagnosticEvent.SOS_KEY_EVALUATED,
            SosEvidence(trigger = SosTrigger.VOLUME_UP, count = count),
        )
    }
    private lateinit var status: TextView
    private var initialized = false
    private lateinit var startButton: Button
    private lateinit var readiness: TextView
    private lateinit var locationAction: Button
    private lateinit var gpsAction: Button
    private lateinit var notificationAction: Button
    private lateinit var saverAction: Button
    private lateinit var backgroundAction: Button
    private val preflight by lazy { FieldPreflight(this) }
    private var pendingStart = false
    private var savingStart = false
    private var startSave: Job? = null
    private var permissionInFlight: Int? = null
    private var askedLocation = false
    private var askedNotifications = false
    private var resumed = false
    private var visible = false
    private val trackingIntent by lazy { Intent(this, TrackingService::class.java) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStart = savedInstanceState?.getBoolean("pendingStart") ?: false
        askedLocation = savedInstanceState?.getBoolean("askedLocation") ?: false
        askedNotifications = savedInstanceState?.getBoolean("askedNotifications") ?: false
        permissionInFlight = savedInstanceState?.getInt("permissionInFlight", 0)?.takeIf { it != 0 }
        if (pendingStart) app.operational.update { it.copy(starting = true) }
        if (savedInstanceState?.getBoolean("canceledSave") == true)
            app.operational.update {
                it.copy(
                    starting = false,
                    error =
                        "Start canceled while saving settings. Check settings and tap Start Tracking.",
                )
            }
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
        sosStatus = text("SOS: no saved event", 18f)
        sosStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val sosButton =
            button("SOS — hold to activate") {
                Toast.makeText(
                        this,
                        "Hold SOS briefly to activate. Accessibility: use the long-click action.",
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        sosButton.setTextColor(android.graphics.Color.WHITE)
        sosButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xffa51621.toInt())
        sosButton.minHeight = dp(64)
        sosButton.setOnLongClickListener {
            app.activateSos(SosTrigger.SCREEN)
            true
        }
        text(
            "Saved on phone ≠ Received by Command. Operator acknowledgement is visible only on Command."
        )
        keyOption =
            CheckBox(this).apply {
                text = getString(R.string.sos_volume_option)
                minHeight = dp(48)
                isChecked = getPreferences(MODE_PRIVATE).getBoolean("volumeSos", false)
                setOnCheckedChangeListener { _, checked ->
                    volumePattern.reset()
                    getPreferences(MODE_PRIVATE).edit().putBoolean("volumeSos", checked).apply()
                }
                content.addView(this)
            }
        text(
            "Physical key support: foreground app only. Three short presses within 1.5 s. Volume still changes. Locked screen / other apps: unsupported; open app and hold SOS."
        )
        scope.launch {
            combine(app.repository.dao.observeSos(), app.sosNotice, app.operational) {
                    rows,
                    notice,
                    op ->
                    sosStatus.text = buildString {
                        notice?.let { appendLine(it) }
                        if (rows.isEmpty()) appendLine("SOS: no saved event")
                        for (row in rows.take(5)) {
                            val m = Protocol.decodeMessage(row.json)
                            appendLine(
                                "SOS ${sosReference(row.messageId)} • ${m.sos!!.triggered_at}"
                            )
                            appendLine(
                                row.sosDescription(
                                    op.health.wifi_connected,
                                    app.activityVisible || op.tracking,
                                )
                            )
                        }
                        if (rows.size > 5)
                            appendLine("${rows.size} SOS events retained; latest five shown")
                        append("Operator acknowledgement: Command-only")
                    }
                }
                .catch { sosStatus.text = getString(R.string.sos_read_failed) }
                .collect {}
        }
        status = text("Loading saved settings…")
        text("Field readiness", 20f)
        readiness = text("Checking field setup…")
        locationAction =
            button("Allow precise location") {
                if (
                    !askedLocation ||
                        shouldShowRequestPermissionRationale(
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                )
                    askPermission(1)
                else
                    openSettings(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        )
                    )
            }
        gpsAction =
            button("Open Location settings") {
                openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        notificationAction =
            button("Allow notifications / notification settings") {
                val snapshot = preflight.snapshot()
                if (
                    Build.VERSION.SDK_INT >= 33 &&
                        !snapshot.notificationPermission &&
                        (!askedNotifications ||
                            shouldShowRequestPermissionRationale(
                                Manifest.permission.POST_NOTIFICATIONS
                            ))
                )
                    askPermission(2)
                else
                    openSettings(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
            }
        saverAction =
            button("Open Battery Saver settings") {
                openSettings(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
        backgroundAction =
            button("Battery/background settings") {
                openSettings(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
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
        text("Diagnostics", 20f)
        button("Export diagnostics") {
            if (!exporting) {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(
                                Intent.EXTRA_TITLE,
                                "gnss-diagnostics-${java.time.Instant.now().toString().replace(':', '-')}.zip",
                            )
                        },
                        EXPORT_DIAGNOSTICS,
                    )
                } catch (e: android.content.ActivityNotFoundException) {
                    exportStatus.text = getString(R.string.diagnostics_no_picker)
                }
            }
        }
        exportStatus =
            text("Incident evidence is saved automatically. Export does not stop tracking.")
        text("Tracking", 20f)
        startButton = button("Loading settings…") { save(start = true) }
        startButton.isEnabled = false
        button("Stop Tracking") {
            AlertDialog.Builder(this)
                .setTitle("Stop tracking on this phone?")
                .setMessage(
                    "Location collection and sending will stop. Saved pending messages remain on this phone until tracking starts again."
                )
                .setNegativeButton("Keep tracking", null)
                .setPositiveButton("Stop Tracking") { _, _ ->
                    startSave?.cancel()
                    pendingStart = false
                    savingStart = false
                    app.operational.update {
                        it.copy(
                            starting = false,
                            stopping = true,
                            error = if (it.starting) null else it.error,
                        )
                    }
                    // Stop acquisition immediately; finish accepted session metadata
                    // before ending it. A later Start cannot race this explicit Stop.
                    stopService(trackingIntent)
                    app.finishTracking()
                }
                .show()
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { app.repository.state() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.cannot_load_settings, e.message)
                return@launch
            }
            combine(
                    app.repository.dao.observeState(),
                    app.repository.dao.queueCounts(),
                    app.repository.dao.blockedError(),
                    app.operational,
                ) { state, queues, blockedError, op ->
                    if (state != null) {
                        if (!initialized) {
                            partyId.setText(state.partyId)
                            partyName.setText(state.partyName)
                            endpoint.setText(state.endpoint)
                            interval.setText(
                                getString(R.string.interval_value, state.localInterval)
                            )
                            initialized = true
                            updateStartControls()
                            attemptStart()
                        }
                        status.text = buildString {
                            appendLine(
                                if (op.starting) "Starting…"
                                else if (op.tracking) "Tracking active"
                                else "Tracking stopped — start to collect and send"
                            )
                            appendLine(op.fixDescription())
                            appendLine("Command: ${state.endpoint.ifEmpty { "Not configured" }}")
                            appendLine("${op.link} • last ACK: ${state.lastAck ?: "None"}")
                            appendLine(
                                "Reporting: local ${state.localInterval} s • effective ${state.config().effective_reporting_interval_s} s"
                            )
                            if (state.overrideSeconds != null)
                                appendLine("Command override active (${state.overrideSeconds} s)")
                            appendLine(
                                "Battery: ${op.health.battery_percent?.let { "$it%" } ?: "Unavailable"} • pending: ${queues.pending}"
                            )
                            appendLine(
                                "Pending GNSS: ${queues.gnss} • SOS: ${queues.sos} • routine: ${queues.routine}"
                            )
                            if (queues.blocked > 0)
                                appendLine(
                                    "${queues.blocked} saved message(s) need attention: ${blockedError ?: "Delivery blocked"}"
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
                        updateStartControls()
                    }
                }
                .catch { e ->
                    initialized = false
                    updateStartControls()
                    status.text = getString(R.string.cannot_read_settings, e.message)
                }
                .collect {}
        }
    }

    private fun save(start: Boolean = false, reenroll: Boolean = false) {
        if (!initialized) {
            app.operational.update {
                it.copy(error = "Settings are still loading. Start becomes available when ready.")
            }
            return
        }
        if (start) {
            if (pendingStart || app.operational.value.starting || app.operational.value.tracking)
                return
            pendingStart = true
            savingStart = true
            app.operational.update { it.copy(starting = true, error = null) }
            updateStartControls()
        }
        val id = partyId.text.toString().trim()
        val name = partyName.text.toString().trim()
        val url = endpoint.text.toString().trim()
        val seconds = interval.text.toString().toIntOrNull()
        val saveJob =
            scope.launch {
                try {
                    require(seconds != null) { "Enter a whole number of seconds" }
                    if (start)
                        require(url.isNotEmpty()) { "Configure a Command URL before tracking" }
                    withContext(Dispatchers.IO) {
                        app.repository.settings(id, name, url, seconds, reenroll)
                    }
                    if (start) {
                        savingStart = false
                        attemptStart()
                    } else
                        Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT)
                            .show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (start) {
                        pendingStart = false
                        savingStart = false
                        app.operational.update { it.copy(starting = false, error = e.message) }
                        updateStartControls()
                    }
                    if (!resumed) return@launch
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Check settings")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        if (start) startSave = saveJob
    }

    private fun updateStartControls() {
        if (!::startButton.isInitialized) return
        val op = app.operational.value
        startButton.isEnabled =
            initialized && !pendingStart && !op.starting && !op.stopping && !op.tracking
        startButton.text =
            when {
                !initialized -> "Loading settings…"
                op.stopping -> "Stopping…"
                op.tracking -> "Tracking active"
                pendingStart || op.starting -> "Starting…"
                else -> "Start Tracking"
            }
    }

    private fun refreshPreflight(): FieldReadiness {
        val state = preflight.snapshot()
        // A later revocation must be requestable again. A denial remains explained
        // without repeatedly reopening the same permission dialog on each resume.
        if (state.precise) askedLocation = false
        if (state.notificationPermission) askedNotifications = false
        readiness.text = state.description()
        locationAction.visibility = if (state.precise) View.GONE else View.VISIBLE
        gpsAction.visibility = if (state.gps == true) View.GONE else View.VISIBLE
        notificationAction.visibility = if (state.notifications == true) View.GONE else View.VISIBLE
        saverAction.visibility = if (state.powerSave == false) View.GONE else View.VISIBLE
        // Keep app settings available even when Android's allowlist is present:
        // OEM background/autostart controls still need human verification.
        return state
    }

    private fun askPermission(code: Int) {
        if (!visible || !resumed || permissionInFlight != null) return
        permissionInFlight = code
        if (code == 1) {
            askedLocation = true
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                code,
            )
        } else if (Build.VERSION.SDK_INT >= 33) {
            askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), code)
        } else permissionInFlight = null
    }

    private fun promptMissingPermissions() {
        if (!resumed || !visible || permissionInFlight != null) return
        val state = refreshPreflight()
        if (!state.precise && !askedLocation) askPermission(1)
        else if (
            Build.VERSION.SDK_INT >= 33 && !state.notificationPermission && !askedNotifications
        )
            askPermission(2)
    }

    private fun attemptStart() {
        if (!pendingStart || savingStart || !initialized) return
        if (!visible || !resumed || permissionInFlight != null) {
            app.operational.update {
                it.copy(error = "Starting is waiting for the visible app and permission result.")
            }
            return
        }
        val state = refreshPreflight()
        val missing =
            when {
                !state.precise -> "Allow precise location to continue starting."
                state.gps != true -> "Enable System Location/GPS in Settings to continue starting."
                state.notifications != true -> "Allow tracking notifications to continue starting."
                state.powerSave == true ->
                    "Battery Saver is ON — turn it OFF for field tracking, then return to continue starting."
                else -> null
            }
        if (missing != null) {
            app.operational.update { it.copy(error = missing) }
            promptMissingPermissions()
            return
        }
        requestStart()
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: android.content.ActivityNotFoundException) {
                app.operational.update {
                    it.copy(error = "Open Android Settings manually to resolve field setup.")
                }
            }
        }
    }

    private fun requestStart() {
        // Settings/permission work can finish after Home/lock hides this Activity.
        // Only a preserved explicit Start may continue on resume. Never create a
        // background location FGS or start tracking just because the app opens.
        if (!visible || isFinishing || isDestroyed) {
            app.operational.update {
                it.copy(error = "Settings saved. Tap Start Tracking while the app is visible.")
            }
            return
        }
        if (!resumed || permissionInFlight != null || app.operational.value.stopping) return
        if (app.operational.value.tracking || (app.operational.value.starting && !pendingStart))
            return
        pendingStart = false // Hand off once; service owns all subsequent startup state.
        app.operational.update { it.copy(starting = true, error = null) }
        updateStartControls()
        try {
            startForegroundService(trackingIntent)
        } catch (e: Exception) {
            app.operational.update {
                it.copy(starting = false, error = "Cannot start tracking: ${e.message}")
            }
            updateStartControls()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 || requestCode == 2) {
            permissionInFlight = null
            refreshPreflight()
            // The result can precede onResume. Preserve the explicit Start; dispatch
            // only after the Activity is resumed, never while permission UI hides it.
            scope.launch {
                yield()
                promptMissingPermissions()
                attemptStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        app.recorder.event(
            DiagnosticEvent.ACTIVITY_RESUMED,
            app.diagnostics.value?.serviceGeneration,
        )
        refreshPreflight()
        promptMissingPermissions()
        attemptStart()
    }

    override fun onPause() {
        resumed = false
        volumePattern.reset()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingStart", pendingStart && !savingStart)
        outState.putBoolean("canceledSave", pendingStart && savingStart)
        outState.putBoolean("askedLocation", askedLocation)
        outState.putBoolean("askedNotifications", askedNotifications)
        outState.putInt("permissionInFlight", permissionInFlight ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        app.activityVisible = true
        app.recorder.event(
            DiagnosticEvent.ACTIVITY_VISIBLE,
            app.diagnostics.value?.serviceGeneration,
        )
    }

    override fun onStop() {
        visible = false
        app.activityVisible = false
        app.recorder.event(
            DiagnosticEvent.ACTIVITY_BACKGROUND,
            app.diagnostics.value?.serviceGeneration,
        )
        super.onStop()
    }

    @Deprecated("Platform document result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_DIAGNOSTICS || resultCode != RESULT_OK || exporting) return
        val uri = data?.data ?: return
        exporting = true
        exportStatus.text = getString(R.string.diagnostics_exporting)
        // Snapshot ZIP on the recorder worker, then copy to the user-selected document on IO.
        // A slow document provider cannot stall the recorder or any tracking loop.
        scope.launch {
            var temporary: java.io.File? = null
            try {
                withContext(Dispatchers.IO) {
                    val file = java.io.File.createTempFile("gnss-export-", ".zip", cacheDir)
                    temporary = file
                    val info = packageManager.getPackageInfo(packageName, 0)
                    val versionCode =
                        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                        else {
                            @Suppress("DEPRECATION") info.versionCode.toLong()
                        }
                    app.recorder.export(
                        file,
                        DiagnosticBuild(
                            info.versionName ?: "unknown",
                            versionCode,
                            Build.VERSION.SDK_INT,
                            BuildConfig.SOURCE_REVISION,
                        ),
                    )
                    contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("Cannot open selected document")
                }
                exportStatus.text = getString(R.string.diagnostics_exported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exportStatus.text = getString(R.string.diagnostics_export_failed)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                exporting = false
            }
        }
    }

    companion object {
        internal const val EXPORT_DIAGNOSTICS = 40
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            resumed &&
                ::keyOption.isInitialized &&
                keyOption.isChecked &&
                event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)
        ) {
            if (
                volumePattern.key(
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_UP,
                    event.action == KeyEvent.ACTION_DOWN,
                    event.repeatCount,
                    event.eventTime,
                    event.isCanceled,
                )
            )
                app.activateSos(SosTrigger.VOLUME_UP)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (pendingStart && (!isChangingConfigurations || savingStart))
            app.operational.update { it.copy(starting = false) }
        scope.cancel()
        super.onDestroy()
    }
}
