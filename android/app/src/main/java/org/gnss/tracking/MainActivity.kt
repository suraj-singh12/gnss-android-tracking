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
    private lateinit var physicalButtonPanel: LinearLayout
    private lateinit var physicalButtonSummary: TextView
    private lateinit var physicalButtonEvents: TextView
    private lateinit var physicalButtonStatus: TextView
    private lateinit var physicalButtonStart: Button
    private lateinit var physicalButtonStop: Button
    private var physicalButtonRefresh: Job? = null
    private var exporting = false
    private lateinit var sosStatus: TextView
    private lateinit var sosDetails: TextView
    private lateinit var emergencyDock: LinearLayout
    private lateinit var sosHold: SosHoldGesture
    private lateinit var keyOption: CheckBox
    private val volumePattern = TripleVolumeUp { count ->
        app.recorder.sos(
            DiagnosticEvent.SOS_KEY_EVALUATED,
            SosEvidence(trigger = SosTrigger.VOLUME_UP, count = count),
        )
    }
    private lateinit var trackingSummary: TextView
    private lateinit var trackingError: TextView
    private lateinit var gnssSummary: TextView
    private lateinit var commandSummary: TextView
    private lateinit var historySummary: TextView
    private lateinit var batterySummary: TextView
    private lateinit var cancelStartButton: Button
    private var destination = "Tracking"
    private lateinit var status: TextView
    private lateinit var diagnosticsSummary: TextView
    private lateinit var settingsFeedback: TextView
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

    private fun styleSecondary(button: Button) {
        button.textSize = 14f
        button.setPadding(dp(12), dp(8), dp(12), dp(8))
        button.setTextColor(
            android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(0xff24343b.toInt(), 0xff829189.toInt()),
            )
        )
        val surface =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xffb2c2bc.toInt())
            }
        button.background =
            android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x3317654c),
                surface,
                null,
            )
    }

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
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xfff3f5f4.toInt())
            }
        val navigation =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        val pages =
            listOf("Tracking", "Settings", "Diagnostics").associateWith {
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(24))
                }
            }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages.values.forEach { pageContainer.addView(it) }
        scroll.addView(pageContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navigation)
        val tabButtons = mutableMapOf<String, Button>()
        fun navigate(name: String) {
            if (::sosHold.isInitialized) sosHold.cancel()
            destination = name
            if (::emergencyDock.isInitialized)
                emergencyDock.visibility = if (name == "Tracking") View.VISIBLE else View.GONE
            pages.forEach { (key, page) ->
                page.visibility = if (key == name) View.VISIBLE else View.GONE
            }
            tabButtons.forEach { (key, tab) ->
                tab.isSelected = key == name
                tab.setTextColor(if (key == name) 0xff17654c.toInt() else 0xff607077.toInt())
                tab.contentDescription = "$key${if (key == name) ", selected" else ""}"
            }
            scroll.scrollTo(0, 0)
        }
        for (name in pages.keys) {
            val tab =
                Button(this).apply {
                    text = name
                    isAllCaps = false
                    minHeight = dp(56)
                    textSize = 12f
                    minWidth = 0
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    setOnClickListener { navigate(name) }
                }
            tabButtons[name] = tab
            navigation.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
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
        var content = pages.getValue("Tracking")
        fun text(label: String, size: Float = 16f): TextView =
            TextView(this).apply {
                text = label
                textSize = size
                setTextColor(0xff24343b.toInt())
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
                styleSecondary(this)
                setOnClickListener { action() }
                content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        fun group(title: String, expanded: Boolean = true): LinearLayout {
            val parent = content
            val card =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(16))
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.WHITE)
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), 0xffdce3df.toInt())
                        }
                    parent.addView(
                        this,
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
                    )
                }
            content = card
            val body =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (expanded) View.VISIBLE else View.GONE
                }
            val toggle =
                button(title + if (expanded) "  −" else "  +") {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    (card.getChildAt(0) as Button).apply {
                        text = title + if (open) "  −" else "  +"
                        contentDescription = "$title, ${if (open) "expanded" else "collapsed"}"
                    }
                }
            toggle.contentDescription = "$title, ${if (expanded) "expanded" else "collapsed"}"
            card.addView(body)
            content = parent
            return body
        }
        fun physicalButtonAction(label: String, action: () -> Unit) =
            Button(this).apply {
                text = label
                minHeight = dp(48)
                isAllCaps = false
                styleSecondary(this)
                setOnClickListener { action() }
                physicalButtonPanel.addView(
                    this,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
                )
            }
        text("GNSS Tracker", 24f)
        trackingSummary = text("STOPPED", 32f)
        trackingSummary.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        trackingError =
            text("").apply {
                setTextColor(0xffa51621.toInt())
                visibility = View.GONE
            }
        gnssSummary = text("GNSS · No fix")
        commandSummary = text("Command · Offline")
        historySummary = text("History · Loading…")
        batterySummary = text("Battery · Unavailable")
        for (card in listOf(gnssSummary, commandSummary, historySummary, batterySummary)) {
            card.setPadding(dp(16), dp(12), dp(16), dp(12))
            card.background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), 0xffdce3df.toInt())
                }
            (card.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        sosStatus = text("SOS · No saved event", 16f)
        sosStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val sosButton =
            button("SOS — hold to activate") {
                Toast.makeText(
                        this,
                        "Hold continuously for 1.2 seconds. Release to cancel. Accessibility: use the long-click action.",
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        sosButton.setTextColor(android.graphics.Color.WHITE)
        sosButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xffa51621.toInt())
        sosButton.minHeight = dp(64)
        val holdProgress =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                contentDescription = "SOS hold progress"
                progressTintList = android.content.res.ColorStateList.valueOf(0xffa51621.toInt())
            }
        sosHold =
            SosHoldGesture(sosButton, holdProgress, { resumed && destination == "Tracking" }) {
                app.activateSos(SosTrigger.SCREEN)
            }
        // Keep the deliberate emergency action reachable even with large fonts or backlog.
        content.removeView(sosStatus)
        content.removeView(sosButton)
        emergencyDock =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
                setBackgroundColor(android.graphics.Color.WHITE)
                addView(sosStatus, LinearLayout.LayoutParams(-1, -2))
                addView(sosButton, LinearLayout.LayoutParams(-1, -2))
                addView(holdProgress, LinearLayout.LayoutParams(-1, dp(4)))
            }
        root.addView(emergencyDock, root.childCount - 1)
        content = pages.getValue("Settings")
        text("Settings", 24f)
        text("Identity, Command connection and field setup.", 14f)
        val emergencySettings = group("Emergency shortcut", false)
        content = emergencySettings
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
        content = pages.getValue("Diagnostics")
        text("Diagnostics", 24f)
        text("Operational evidence · safe to inspect while tracking.", 14f)
        diagnosticsSummary = text("Loading operational summary…")
        content = group("SOS event details", false)
        sosDetails = text("No saved SOS event")
        content = pages.getValue("Tracking")
        scope.launch {
            combine(app.repository.dao.observeSos(), app.sosNotice, app.operational) {
                    rows,
                    notice,
                    op ->
                    val pending = rows.count { it.deliveredAt == null }
                    val relevant = rows.firstOrNull { it.deliveredAt == null } ?: rows.firstOrNull()
                    sosStatus.text =
                        when {
                            relevant == null -> notice ?: "SOS · No saved event"
                            pending > 0 -> "SOS Pending · $pending awaiting Command receipt"
                            else -> "SOS Received · Operator acknowledgement on Command"
                        }
                    if (
                        notice != null &&
                            (notice.contains("saving", ignoreCase = true) ||
                                notice.contains("failed", ignoreCase = true))
                    )
                        sosStatus.text = "$notice\n${sosStatus.text}"
                    sosDetails.text = buildString {
                        notice?.let { appendLine(it) }
                        if (rows.isEmpty()) appendLine("SOS: no saved event")
                        for (row in rows.take(5)) {
                            val m = Protocol.decodeMessage(row.json)
                            appendLine(
                                "SOS ${sosReference(row.messageId)} • ${m.sos!!.triggered_at}"
                            )
                            appendLine(
                                (if (row.deliveredAt != null) "SOS Received · "
                                else "SOS Pending · ") +
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
        content = pages.getValue("Diagnostics")
        content = group("Advanced technical evidence", false)
        status = text("Loading saved settings…")
        content = pages.getValue("Tracking")
        button("Check setup / permissions") { navigate("Settings") }
        content = pages.getValue("Settings")
        val fieldSettings = group("Permissions and battery readiness", false)
        content = fieldSettings
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
        content = pages.getValue("Settings")
        val connectionSettings = group("Party and Command")
        content = connectionSettings
        text("Party identity", 18f)
        partyId = edit("Party ID")
        partyName = edit("Party name")
        text("Command connection", 18f)
        endpoint = edit("Command base URL (local Wi-Fi)")
        endpoint.hint = "http://192.168.1.10:8080"
        interval = edit("Local reporting interval (seconds, 5–86400 in steps of 5)", true)
        button("Save settings") { save() }
        settingsFeedback =
            text("Changes apply only when saved. Command overrides remain authoritative.", 14f)
        val settingsPage = pages.getValue("Settings")
        val connectionCard = connectionSettings.parent as View
        settingsPage.removeView(connectionCard)
        settingsPage.addView(connectionCard, 2)
        content = pages.getValue("Diagnostics")
        val diagnosticTools =
            LinearLayout(this).apply {
                id = R.id.diagnostic_tools
                orientation = LinearLayout.VERTICAL
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        content = diagnosticTools
        text("Diagnostic tools", 20f)
        text("Inspect and export evidence while tracking continues.")
        button("Physical Button Test") {
            physicalButtonPanel.visibility =
                if (physicalButtonPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (physicalButtonPanel.visibility == View.VISIBLE) {
                refreshPhysicalButtonHistory()
                scroll.post { scroll.smoothScrollTo(0, physicalButtonPanel.bottom) }
            }
        }
        physicalButtonPanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(12))
                visibility = View.GONE
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xffffffff.toInt())
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), 0xffdce3df.toInt())
                    }
                content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        TextView(this).apply {
            text = "Physical Button Test"
            textSize = 20f
            setTextColor(0xff24343b.toInt())
            contentDescription = "Physical Button Test details"
            physicalButtonPanel.addView(this)
        }
        physicalButtonStatus =
            TextView(this).apply {
                textSize = 16f
                setTextColor(0xff24343b.toInt())
                setPadding(0, dp(8), 0, dp(8))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                physicalButtonPanel.addView(this)
            }
        TextView(this).apply {
            text =
                "Only supported keys delivered to this visible Activity are recorded. Background / locked-screen / screen-off volume-button detection: Mechanism unavailable. No MediaSession or background listener is used."
            textSize = 14f
            setTextColor(0xff24343b.toInt())
            physicalButtonPanel.addView(this)
        }
        TextView(this).apply {
            text =
                "While this test is active, the triple-Volume-Up SOS trigger is paused. Android volume handling and previously saved SOS delivery continue. This test never creates an SOS."
            textSize = 14f
            setTextColor(0xff24343b.toInt())
            physicalButtonPanel.addView(this)
        }
        physicalButtonStart =
            physicalButtonAction("Start Test") {
                volumePattern.reset()
                app.physicalButtonTest.start()
                refreshPhysicalButtonHistory()
            }
        physicalButtonStop =
            physicalButtonAction("Stop Test") {
                volumePattern.reset()
                app.physicalButtonTest.stop()
                refreshPhysicalButtonHistory()
            }
        physicalButtonAction("Clear Results") {
            app.physicalButtonTest.clearResults()
            refreshPhysicalButtonHistory()
        }
        physicalButtonSummary =
            TextView(this).apply {
                textSize = 14f
                setTextColor(0xff24343b.toInt())
                setPadding(0, dp(8), 0, dp(8))
                physicalButtonPanel.addView(this)
            }
        ScrollView(this).apply {
            isFillViewport = false
            addView(
                TextView(this@MainActivity).apply {
                    textSize = 14f
                    setTextColor(0xff24343b.toInt())
                    setTextIsSelectable(true)
                    physicalButtonEvents = this
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            physicalButtonPanel.addView(this, LinearLayout.LayoutParams(-1, dp(176)))
        }
        renderPhysicalButtonStatus()
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
        content = connectionSettings
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
        content = diagnosticTools
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
        pages.getValue("Diagnostics").apply {
            removeView(diagnosticTools)
            addView(diagnosticTools, 3)
        }
        content = pages.getValue("Tracking")
        startButton =
            button("Loading settings…") {
                if (app.operational.value.tracking) stopTracking() else save(start = true)
            }
        startButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xff17654c.toInt())
        startButton.setTextColor(android.graphics.Color.WHITE)
        startButton.minHeight = dp(56)
        startButton.isEnabled = false
        cancelStartButton = button("Cancel Start") { stopTracking() }
        cancelStartButton.visibility = View.GONE
        content.removeView(startButton)
        content.addView(startButton, 2)
        navigate(savedInstanceState?.getString("destination") ?: "Tracking")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { app.repository.state() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.cannot_load_settings, e.message)
                trackingSummary.text = "ERROR"
                trackingError.text = status.text
                trackingError.visibility = View.VISIBLE
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
                        trackingSummary.text =
                            when {
                                op.starting -> "STARTING"
                                op.stopping -> "STOPPING"
                                op.tracking -> "TRACKING"
                                op.error != null || state.operationalError != null ->
                                    "NEEDS ATTENTION"
                                else -> "STOPPED"
                            }
                        trackingError.text = op.error ?: state.operationalError ?: ""
                        trackingError.setTextColor(
                            if (op.tracking) 0xff80590c.toInt() else 0xffa51621.toInt()
                        )
                        trackingError.visibility =
                            if (trackingError.text.isEmpty()) View.GONE else View.VISIBLE
                        gnssSummary.text =
                            (if (op.tracking) "GNSS\n"
                            else "Last known GNSS · Tracking stopped\n") +
                                op.fixDescription()
                                    .replace("GPS: Fix", "GNSS Fresh")
                                    .replace("GPS: Stale fix", "GNSS Stale")
                                    .replace("GPS: ", "")
                        val connection =
                            when {
                                !op.tracking -> "Offline · Tracking stopped"
                                op.link.contains("Command reachable") -> "Command Connected"
                                op.health.wifi_connected == true ->
                                    "Reconnecting · Command unavailable"
                                else -> "Offline"
                            }
                        commandSummary.text =
                            "Command\n$connection\nLast successful communication: ${state.lastAck ?: "None"}"
                        historySummary.text =
                            "History\n" +
                                when {
                                    queues.blocked > 0 ||
                                        state.deliveryPaused ||
                                        state.knownCollectionLoss > 0 ||
                                        app.observationPersistence.state.awaitingCommit > 0 ->
                                        "Delivery problem · ${queues.gnss} GNSS pending"
                                    queues.gnss == 0 -> "Fully synchronized"
                                    connection == "Command Connected" ->
                                        "Synchronizing · ${queues.gnss} GNSS pending"
                                    else -> "${queues.gnss} observations pending"
                                }
                        batterySummary.text =
                            "Battery · ${op.health.battery_percent?.let { "$it%" } ?: "Unavailable"}${if (op.health.charging == true) " · Charging" else ""}"
                        diagnosticsSummary.text = buildString {
                            appendLine("Tracking · ${if (op.tracking) "Active" else "Stopped"}")
                            appendLine(op.fixDescription().replace("GPS:", "GNSS:"))
                            appendLine("Command · $connection")
                            appendLine(
                                "Local evidence · ${state.observationSequence} observations recorded"
                            )
                            appendLine("Pending delivery · ${queues.gnss} GNSS · ${queues.sos} SOS")
                            appendLine(
                                "Persistence · ${app.observationPersistence.state.awaitingCommit} awaiting commit · ${app.observationPersistence.state.writeFailures} write failures"
                            )
                            append(
                                "Export includes the complete technical journal and physical-button report."
                            )
                        }
                        status.text = buildString {
                            appendLine("Device: ${state.deviceId}")
                            appendLine(
                                "GNSS observations: ${state.observationSequence} · known collection loss: ${state.knownCollectionLoss}"
                            )
                            val collection = app.observationPersistence.state
                            appendLine(
                                "Persistence: ${collection.awaitingCommit} awaiting commit · ${collection.writeFailures} failures"
                            )
                            app.diagnostics.value?.let { diagnostic ->
                                appendLine("Network: ${diagnostic.network}")
                                appendLine("Transport: ${diagnostic.sender}")
                                appendLine(
                                    "Last ACK age: ${diagnostic.lastAckAgeMs?.let { "${it / 1000} s" } ?: "Unavailable"}"
                                )
                            }
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
                    trackingSummary.text = "ERROR"
                    trackingError.text = status.text
                    trackingError.visibility = View.VISIBLE
                }
                .collect {}
        }
    }

    private fun stopTracking() {
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
                    } else {
                        settingsFeedback.text =
                            "Settings saved · ${seconds} s local reporting. Command overrides remain applied."
                        Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT)
                            .show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (start) {
                        pendingStart = false
                        savingStart = false
                        app.operational.update { it.copy(starting = false, error = e.message) }
                        updateStartControls()
                    }
                    settingsFeedback.text =
                        "Settings not saved · ${e.message ?: "Check the entered values"}"
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
        startButton.isEnabled = initialized && !pendingStart && !op.starting && !op.stopping
        cancelStartButton.visibility = if (pendingStart || op.starting) View.VISIBLE else View.GONE
        startButton.text =
            when {
                !initialized -> "Loading settings…"
                op.stopping -> "Stopping…"
                op.tracking -> "Stop Tracking"
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
        app.physicalButtonTest.activityResumed()
        if (::physicalButtonPanel.isInitialized && physicalButtonPanel.visibility == View.VISIBLE)
            refreshPhysicalButtonHistory()
        app.recorder.event(
            DiagnosticEvent.ACTIVITY_RESUMED,
            app.diagnostics.value?.serviceGeneration,
        )
        refreshPreflight()
        promptMissingPermissions()
        attemptStart()
    }

    override fun onPause() {
        sosHold.cancel()
        app.physicalButtonTest.activityPaused()
        resumed = false
        volumePattern.reset()
        renderPhysicalButtonStatus()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("destination", destination)
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
        if (resumed && app.physicalButtonTest.listening) {
            val interactive =
                runCatching { getSystemService(android.os.PowerManager::class.java).isInteractive }
                    .getOrNull()
            val locked =
                runCatching {
                        getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                    }
                    .getOrNull()
            app.physicalButtonTest.observeActivityKey(
                event.keyCode,
                event.action,
                event.repeatCount,
                appForeground = resumed,
                screenInteractive = interactive,
                keyguardLocked = locked,
            )
            schedulePhysicalButtonRefresh()
            // The experiment only observes; Android still receives normal key handling.
        } else if (
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

    private fun renderPhysicalButtonStatus(history: PhysicalButtonHistory? = null) {
        if (!::physicalButtonStatus.isInitialized) return
        val active = app.physicalButtonTest.listening && resumed
        physicalButtonStatus.text =
            when {
                active -> "Test status: LISTENING — Activity-visible keys only"
                history?.activityWasInterrupted == true ->
                    "Test status: INTERRUPTED — SOS key trigger restored"
                else -> "Test status: INACTIVE"
            }
        physicalButtonStart.isEnabled = !app.physicalButtonTest.listening
        physicalButtonStop.isEnabled = app.physicalButtonTest.listening
        if (history == null) return
        val last = history.retainedEvents.lastOrNull()
        val localTime = { value: String? ->
            value?.let {
                runCatching {
                        java.time.Instant.parse(it)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                    }
                    .getOrDefault("unavailable")
            } ?: "None"
        }
        physicalButtonSummary.text = buildString {
            appendLine("Test start time: ${localTime(history.startedAt)}")
            appendLine("Last detected key: ${last?.evidence?.keyName ?: "None"}")
            appendLine(
                "Last detected timestamp: ${localTime(last?.at)}${last?.let { " (${it.evidence.action})" } ?: ""}"
            )
            appendLine("Count of actual key presses: ${history.totalPresses}")
            appendLine("Event source: Activity")
            appendLine(
                "Device: ${history.manufacturer ?: "unavailable"} ${history.model ?: "unavailable"}"
            )
            appendLine(
                "Android: ${history.androidVersion ?: "unavailable"} (API ${history.androidApi ?: "unavailable"})"
            )
            appendLine("Activity result: ${history.activityResult}")
            appendLine("Activity test: ${history.activityReason}")
            append(PhysicalButtonTestController.LOCKED_SCREEN_STATUS)
        }
        physicalButtonEvents.text = buildString {
            for (event in history.retainedEvents) {
                append(localTime(event.at))
                append("  ${event.evidence.keyName} ${event.evidence.action}  Activity")
                if (event.evidence.repeatCount > 0) append(" repeat=${event.evidence.repeatCount}")
                append("  screen=")
                append(
                    when {
                        event.evidence.screenInteractive == false -> "off"
                        event.evidence.screenInteractive == true -> "on"
                        else -> "unknown"
                    }
                )
                append(" locked=${event.evidence.keyguardLocked ?: "unknown"}")
                appendLine()
            }
            if (history.olderEventsOmitted)
                appendLine(
                    "Showing the latest ${PhysicalButtonTestController.MAX_DISPLAY_EVENTS} events."
                )
            if (isEmpty()) append("No key events recorded yet.")
        }
    }

    private fun refreshPhysicalButtonHistory() {
        if (!::physicalButtonPanel.isInitialized || physicalButtonPanel.visibility != View.VISIBLE)
            return
        renderPhysicalButtonStatus()
        schedulePhysicalButtonRefresh()
    }

    private fun schedulePhysicalButtonRefresh() {
        if (!::physicalButtonPanel.isInitialized || physicalButtonPanel.visibility != View.VISIBLE)
            return
        physicalButtonRefresh?.cancel()
        physicalButtonRefresh =
            scope.launch {
                delay(125)
                try {
                    val history =
                        withContext(Dispatchers.IO) { app.recorder.physicalButtonHistory() }
                    renderPhysicalButtonStatus(history)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    physicalButtonSummary.text = getString(R.string.physical_button_read_failed)
                    physicalButtonEvents.text = "No additional result can be verified."
                }
            }
    }

    override fun onDestroy() {
        if (::sosHold.isInitialized) sosHold.cancel()
        if (pendingStart && (!isChangingConfigurations || savingStart))
            app.operational.update { it.copy(starting = false) }
        physicalButtonRefresh?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
