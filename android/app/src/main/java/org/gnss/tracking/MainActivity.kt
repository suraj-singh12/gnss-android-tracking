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
    private lateinit var historyProgressLabel: TextView
    private lateinit var historyProgressBar: ProgressBar
    private var currentQueueCounts: QueueCounts? = null
    private var historyProgressStartDelivered: Long? = null
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
                intArrayOf(getColor(R.color.ink), getColor(R.color.disabled_ink)),
            )
        )
        val surface =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.surface))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), getColor(R.color.control_line))
            }
        button.background =
            android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(getColor(R.color.ripple)),
                surface,
                null,
            )
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val night =
            newBase.getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("night", false)
        val appearanceConfiguration =
            android.content.res.Configuration(newBase.resources.configuration)
        appearanceConfiguration.uiMode =
            (appearanceConfiguration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES
                else android.content.res.Configuration.UI_MODE_NIGHT_NO
        super.attachBaseContext(newBase.createConfigurationContext(appearanceConfiguration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val night = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("night", false)
        setTheme(if (night) R.style.AppThemeNight else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        pendingStart = savedInstanceState?.getBoolean("pendingStart") ?: false
        askedLocation = savedInstanceState?.getBoolean("askedLocation") ?: false
        askedNotifications = savedInstanceState?.getBoolean("askedNotifications") ?: false
        historyProgressStartDelivered =
            savedInstanceState
                ?.takeIf { it.containsKey("historyProgressStartDelivered") }
                ?.getLong("historyProgressStartDelivered")
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
                setBackgroundColor(getColor(R.color.background))
            }
        val navigation =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(getColor(R.color.surface))
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
                tab.setTextColor(
                    if (key == name) getColor(R.color.accent) else getColor(R.color.muted)
                )
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
                        android.content.res.ColorStateList.valueOf(getColor(R.color.surface))
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
                setTextColor(getColor(R.color.ink))
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
                            setColor(getColor(R.color.surface))
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), getColor(R.color.line))
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
                setTextColor(getColor(R.color.danger))
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
                    setColor(getColor(R.color.surface))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), getColor(R.color.line))
                }
            (card.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        historyProgressLabel =
            TextView(this).apply {
                text = "Saved GNSS sync · waiting for counts"
                textSize = 14f
                setTextColor(getColor(R.color.ink))
                setPadding(dp(12), dp(8), dp(12), dp(4))
            }
        historyProgressBar =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                isIndeterminate = false
                contentDescription = "Saved GNSS synchronization progress"
                progressTintList =
                    android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            }
        val historyIndex = content.indexOfChild(historySummary) + 1
        content.addView(historyProgressLabel, historyIndex, LinearLayout.LayoutParams(-1, -2))
        content.addView(
            historyProgressBar,
            historyIndex + 1,
            LinearLayout.LayoutParams(-1, dp(8)).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(8)
            },
        )
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
                progressTintList =
                    android.content.res.ColorStateList.valueOf(getColor(R.color.danger))
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
                setBackgroundColor(getColor(R.color.surface))
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
                            relevant == null -> "SOS · No saved event"
                            pending > 0 -> "SOS Pending · $pending awaiting Command receipt"
                            else -> "SOS Received · Operator acknowledgement on Command"
                        }
                    // A latest save failure or debounce result must not be hidden
                    // merely because an older SOS is still queued or received.
                    if (notice != null) sosStatus.text = "$notice\n${sosStatus.text}"
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
        val phoneAlertActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(phoneAlertActions)
        scope.launch {
            app.repository.dao.observeCommands().collect { rows ->
                phoneAlertActions.removeAllViews()
                for (row in
                    rows.filter {
                        it.trusted &&
                            it.operation == "alert" &&
                            it.state in listOf("output_attempted", "alert_blocked", "unknown")
                    }) {
                    phoneAlertActions.addView(
                        TextView(this@MainActivity).apply {
                            text =
                                "Command phone alert · ${row.state}\n${runCatching { PhoneAlertSpec.parse(Protocol.parse(row.requested)).message }.getOrDefault("")}\n${row.reason}"
                        }
                    )
                    phoneAlertActions.addView(
                        Button(this@MainActivity).apply {
                            text = "Acknowledge / Stop Alert"
                            minHeight = dp(48)
                            setOnClickListener {
                                app.workScope.launch { app.commandControl.acknowledgeAlert(row.id) }
                            }
                        }
                    )
                }
            }
        }
        button("Sync All Now") {
            // A new manual drain gets its own progress baseline. Repeated presses
            // and Pause -> Resume preserve the in-progress baseline.
            val op = app.operational.value
            if (!app.sender.aggressive && !op.drainStarting && !op.historyPaused)
                historyProgressStartDelivered = currentQueueCounts?.deliveredGnss?.toLong()
            app.syncAllNow()
            if (!app.operational.value.tracking) {
                try {
                    startForegroundService(Intent(this, RetainedTransferService::class.java))
                } catch (e: RuntimeException) {
                    app.operational.update {
                        it.copy(error = "Background transfer unavailable; keep this app visible")
                    }
                }
            }
        }
        button("Pause history sync") { app.pauseHistory() }
        text(
            "Sync All Now uses a visible transfer notification, including with Tracking OFF, without collecting GNSS. Android may stop it; reopen after process death or reboot. Pause retains data; an in-flight receipt may finish."
        )
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
        content = settingsPage
        val appearanceButton =
            button("Appearance: ${if (night) "Night" else "Day"}") {
                AlertDialog.Builder(this)
                    .setTitle("Appearance")
                    .setSingleChoiceItems(arrayOf("Day", "Night"), if (night) 1 else 0) {
                        dialog,
                        choice ->
                        if ((choice == 1) != night) {
                            getSharedPreferences("appearance", MODE_PRIVATE)
                                .edit()
                                .putBoolean("night", choice == 1)
                                .apply()
                            recreate()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        appearanceButton.id = R.id.appearance
        settingsPage.removeView(appearanceButton)
        settingsPage.addView(appearanceButton, 2)
        content = settingsPage
        val persistenceSettings = group("GNSS persistence", false)
        content = persistenceSettings
        text(
            "Adaptive persistence is optional and OFF by default. Tracking controls GNSS collection; SOS uses the latest native fix. Changes affect future fixes only.",
            14f,
        )
        val adaptive =
            android.widget.CheckBox(this).apply {
                text = "Adaptive GNSS persistence"
                minHeight = dp(48)
                isChecked = app.persistencePolicy.state.enabled
                content.addView(this)
            }
        val policyStatus =
            text(
                "Effective: ${if (app.persistencePolicy.state.enabled && app.observationPersistence.samplingState.fallback == null) "Adaptive ON" else "Dense OFF"}",
                14f,
            )
        adaptive.setOnCheckedChangeListener { _, enabled ->
            if (!adaptive.isEnabled) return@setOnCheckedChangeListener
            adaptive.isEnabled = false
            // The saved user action belongs to the application, so rotating or
            // leaving this Activity cannot cancel an accepted preference write.
            app.workScope.launch {
                val result = runCatching {
                    app.persistencePolicy.setEnabled(enabled, utc(SystemClock.wallMillis())).also {
                        app.recorder.event(
                            DiagnosticEvent.PERSISTENCE_POLICY_CHANGED,
                            persistencePolicy = it,
                        )
                    }
                }
                if (result.isFailure)
                    app.recorder.event(
                        DiagnosticEvent.PERSISTENCE_POLICY_FAILED,
                        persistencePolicy = app.persistencePolicy.state,
                    )
                withContext(Dispatchers.Main) {
                    if (isDestroyed) return@withContext
                    result.fold(
                        onSuccess = { policy ->
                            policyStatus.text =
                                "Saved: ${if (policy.enabled) "Adaptive ON" else "Dense OFF"} · applies to future fixes"
                        },
                        onFailure = {
                            adaptive.isChecked = false
                            policyStatus.text =
                                "Policy NOT SAVED · dense persistence effective. Check storage."
                        },
                    )
                    adaptive.isEnabled = true
                }
            }
        }

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
                        setColor(getColor(R.color.surface))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), getColor(R.color.line))
                    }
                content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        TextView(this).apply {
            text = "Physical Button Test"
            textSize = 20f
            setTextColor(getColor(R.color.ink))
            contentDescription = "Physical Button Test details"
            physicalButtonPanel.addView(this)
        }
        physicalButtonStatus =
            TextView(this).apply {
                textSize = 16f
                setTextColor(getColor(R.color.ink))
                setPadding(0, dp(8), 0, dp(8))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                physicalButtonPanel.addView(this)
            }
        TextView(this).apply {
            text =
                "Only supported keys delivered to this visible Activity are recorded. Background / locked-screen / screen-off volume-button detection: Mechanism unavailable. No MediaSession or background listener is used."
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            physicalButtonPanel.addView(this)
        }
        TextView(this).apply {
            text =
                "While this test is active, the triple-Volume-Up SOS trigger is paused. Android volume handling and previously saved SOS delivery continue. This test never creates an SOS."
            textSize = 14f
            setTextColor(getColor(R.color.ink))
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
                setTextColor(getColor(R.color.ink))
                setPadding(0, dp(8), 0, dp(8))
                physicalButtonPanel.addView(this)
            }
        ScrollView(this).apply {
            isFillViewport = false
            addView(
                TextView(this@MainActivity).apply {
                    textSize = 14f
                    setTextColor(getColor(R.color.ink))
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
                    "Clear remote control authorization and reporting override. Secure remote instructions require a new credential verified at the local Command console. Saved messages and device identity are retained."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Re-enroll") { _, _ -> save(reenroll = true) }
                .show()
        }
        content = connectionSettings
        text("Authenticated remote control", 18f)
        val controlPhoneIdentity = text("Loading phone identity…")
        controlPhoneIdentity.setTextIsSelectable(true)
        scope.launch {
            controlPhoneIdentity.text =
                try {
                    "Phone UUID: " + withContext(Dispatchers.IO) { app.repository.state().deviceId }
                } catch (_: Exception) {
                    "Phone identity unavailable: check storage before enrolling"
                }
        }
        val commandCredential =
            EditText(this).apply {
                hint = "One-time credential from local Command console"
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setSaveEnabled(false)
            }
        content.addView(commandCredential)
        val enrollmentStatus =
            text(
                "Remote control is unavailable until deliberately enrolled. Compare this phone UUID with Command. Transfer the credential privately; never obtain it from an unverified LAN server."
            )
        button("Authorize this Command for remote instructions") {
            val value = commandCredential.text.toString()
            commandCredential.text.clear()
            AlertDialog.Builder(this)
                .setTitle("Authorize remote control?")
                .setMessage(
                    "Only continue after verifying the phone identity and credential directly at your local Command console. Allows reporting, adaptive persistence, sync, phone alerts and OS-permitted tracking controls."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Authorize") { _, _ ->
                    scope.launch {
                        enrollmentStatus.text =
                            try {
                                withContext(Dispatchers.IO) {
                                    val key = ControlCrypto.parseCredential(value)
                                    app.commandControl.bind(key) { app.controlKeys.save(key) }
                                }
                                "Remote control enrolled; awaiting authenticated contact"
                            } catch (_: Exception) {
                                "Enrollment failed: check phone identity, credential and secure key storage"
                            }
                    }
                }
                .show()
        }
        button("Disable remote control locally") {
            scope.launch {
                withContext(Dispatchers.IO) { app.commandControl.disable() }
                enrollmentStatus.text = "Remote control disabled; pending results retained"
            }
        }
        content = pages.getValue("Diagnostics")
        val commandGroup = group("Command Instructions", false)
        content = commandGroup
        val commandFilter =
            EditText(this).apply {
                hint = "Filter outcome, action, command ID or UTC date"
                contentDescription = hint
            }
        content.addView(commandFilter)
        val instructionSummary = text("Loading Command Instructions…")
        val commandRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(commandRows)
        var commandHistory = emptyList<CommandRecord>()
        val expandedCommandIds = mutableSetOf<String>()
        fun renderCommands() {
            commandRows.removeAllViews()
            val filter = commandFilter.text.toString().lowercase()
            val matching =
                commandHistory.filter {
                    (it.id + it.operation + it.state + it.reason + utc(it.received))
                        .lowercase()
                        .contains(filter)
                }
            instructionSummary.text =
                "${app.commandControl.availability}\n${matching.size} matching / ${commandHistory.size} retained. Only instructions actually received appear. Pending results are never cleared by this console."
            for (row in matching.take(100)) {
                val title =
                    TextView(this).apply {
                        text =
                            "${utc(row.received)} · ${row.operation} · ${row.state}\n${row.reason}"
                        setPadding(0, 12, 0, 12)
                    }
                commandRows.addView(title)
                val detail =
                    TextView(this).apply {
                        text =
                            "${if(row.trusted) "Command" else "Security event"} ${row.id}\nOrigin: configured Command endpoint (address redacted)\nAuthority ${row.authority}\nPhone ${row.device}\nAuthenticated ${row.trusted} · attempted ${row.attempted}\nVersion ${row.version} · priority ${row.priority} · created ${if(row.created>0) utc(row.created) else "unknown"} · expires ${if(row.expires>0) utc(row.expires) else "unknown"}\nAttempted ${if(row.attemptedAt>0) utc(row.attemptedAt) else "unconfirmed"} · result ${if(row.completedAt>0) utc(row.completedAt) else "pending"} · custody ${if(row.confirmedAt>0) utc(row.confirmedAt) else "unconfirmed"}\nRequested ${row.requested}\nEffective ${row.effective.ifEmpty { "unconfirmed" }}\nResult ${if(row.confirmed) "delivery confirmed" else "pending or unknown delivery"}\n${row.events}"
                        visibility = if (row.id in expandedCommandIds) View.VISIBLE else View.GONE
                        setTextIsSelectable(true)
                    }
                title.contentDescription = "${title.text}. Tap to expand details"
                title.setOnClickListener {
                    val expand = detail.visibility == View.GONE
                    if (expand) expandedCommandIds.add(row.id)
                    else expandedCommandIds.remove(row.id)
                    detail.visibility = if (expand) View.VISIBLE else View.GONE
                }
                commandRows.addView(detail)
                if (
                    row.operation == "alert" &&
                        row.trusted &&
                        row.state in listOf("output_attempted", "alert_blocked", "unknown")
                ) {
                    commandRows.addView(
                        Button(this).apply {
                            text = "Acknowledge / Stop Alert"
                            setOnClickListener {
                                app.workScope.launch { app.commandControl.acknowledgeAlert(row.id) }
                            }
                        }
                    )
                }
                commandRows.addView(
                    Button(this).apply {
                        text = if (row.trusted) "Copy command ID" else "Copy security event ID"
                        setOnClickListener {
                            getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(
                                    android.content.ClipData.newPlainText("Command ID", row.id)
                                )
                        }
                    }
                )
            }
            if (matching.isEmpty())
                commandRows.addView(
                    TextView(this).apply { text = "No received instructions match this filter" }
                )
        }
        commandFilter.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderCommands()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        scope.launch {
            try {
                combine(app.repository.dao.observeCommands(), app.commandControl.updates) { rows, _
                        ->
                        rows
                    }
                    .collect {
                        commandHistory = it.sortedByDescending { row -> row.received }
                        renderCommands()
                    }
            } catch (_: Exception) {
                instructionSummary.text =
                    "Command Instructions unavailable: cannot read durable journal; check storage"
            }
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
                    app.persistencePolicy.updates,
                ) { state, queues, blockedError, op, localPolicy ->
                    if (state != null) {
                        val oldestPending =
                            withContext(Dispatchers.IO) {
                                app.repository.dao.oldestPendingCapturedMillis()?.let { utc(it) }
                            }
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
                            if (op.tracking) getColor(R.color.warning) else getColor(R.color.danger)
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
                                app.sender.commandReachable == true -> "Command Connected"
                                !op.tracking && op.health.wifi_connected == true ->
                                    "Reconnecting · Tracking stopped"
                                op.link.contains("Command reachable") -> "Command Connected"
                                op.health.wifi_connected == true ->
                                    "Reconnecting · Command unavailable"
                                else -> "Offline"
                            }
                        commandSummary.text =
                            "Command\n$connection\nLast successful communication: ${state.lastAck ?: "None"}"
                        currentQueueCounts = queues
                        val progressBaseline =
                            historyProgressStartDelivered
                                ?: queues.deliveredGnss.toLong().also {
                                    historyProgressStartDelivered = it
                                }
                        val sync =
                            savedGnssSyncProgress(
                                progressBaseline,
                                queues.deliveredGnss.toLong(),
                                queues.gnss.toLong(),
                            )
                        historyProgressBar.progress = sync.percentage
                        historyProgressLabel.text =
                            if (sync.total == 0L) "Saved GNSS sync · no observations pending"
                            else
                                "${sync.acknowledged} / ${sync.total} ACKed (${sync.percentage}%) · ${sync.pending} pending" +
                                    if (queues.blocked > 0)
                                        " · ${queues.blocked} unresolved messages"
                                    else ""
                        historyProgressBar.contentDescription =
                            "Saved GNSS sync ${sync.percentage} percent; " +
                                "${sync.acknowledged} of ${sync.total} currently known GNSS observations acknowledged, " +
                                "${sync.pending} pending"
                        historySummary.text =
                            "History · ${queues.deliveredGnss} ACKed · ${queues.blocked} unresolved\n${if (op.historyPaused) "Paused" else app.sender.syncProgress.mode}\n" +
                                when {
                                    queues.blocked > 0 ||
                                        state.deliveryPaused ||
                                        state.knownCollectionLoss > 0 ||
                                        app.observationPersistence.state.awaitingCommit > 0 ->
                                        "Delivery problem · ${queues.gnss} GNSS pending"
                                    queues.gnss == 0 ->
                                        "All saved GNSS durably ACKed · Command reconstruction is separate"
                                    op.historyPaused ->
                                        "History paused · ${queues.gnss} GNSS pending"
                                    connection == "Command Connected" ->
                                        "Synchronizing · ${queues.gnss} GNSS pending"
                                    else -> "${queues.gnss} observations pending"
                                }
                        batterySummary.text =
                            "Battery · ${op.health.battery_percent?.let { "$it%" } ?: "Unavailable"}${if (op.health.charging == true) " · Charging" else ""}"
                        if (adaptive.isEnabled) {
                            adaptive.isEnabled = false
                            adaptive.isChecked = localPolicy.enabled
                            adaptive.isEnabled = true
                            policyStatus.text =
                                "Requested: ${if (localPolicy.enabled) "ON" else "OFF"} · effective: ${if (localPolicy.enabled && app.observationPersistence.samplingState.fallback == null) "Adaptive" else "Dense"}" +
                                    (app.observationPersistence.samplingState.fallback
                                            ?: localPolicy.error)
                                        ?.let { " · $it" }
                                        .orEmpty()
                        }
                        diagnosticsSummary.text = buildString {
                            val fallback = app.observationPersistence.samplingState.fallback
                            appendLine(
                                "GNSS persistence · ${if (localPolicy.enabled && fallback == null) "Adaptive ON" else "Dense OFF"}${if (fallback != null) " · fallback" else ""}"
                            )
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
                            val sampling = app.observationPersistence.samplingState
                            val policy = localPolicy
                            appendLine(
                                "Persistence policy · ${if (policy.enabled && sampling.fallback == null) "Adaptive ON" else "Dense OFF"} · revision ${policy.revision} · changes ${policy.changes} · saved change ${policy.effectiveAt ?: "default"} · first callback applied ${sampling.firstCallbackAppliedAt ?: "none this process"}"
                            )
                            app.diagnostics.value?.source?.let {
                                appendLine(
                                    "This service · ${it.locationCallbacks} native location callbacks · ${it.callbackGaps} arrival gaps >2.5 s · ${it.rejectedObservations} rejected/stale for latest display"
                                )
                            }
                            appendLine(
                                "This Sender · ${app.sender.syncProgress.attemptedGnssRecords} GNSS record attempts · ${app.sender.syncProgress.ackedGnssRecords} GNSS records ACKed (retries may repeat attempts)"
                            )
                            appendLine(
                                "This process · ${sampling.candidates} valid callback candidates · ${app.observationPersistence.state.committedObservations} originals committed · ${sampling.intentionallyNotSelected} intentionally sampled · ${sampling.callbackGaps} valid-candidate arrival gaps"
                            )
                            appendLine(
                                "Boundary candidate in memory: ${sampling.boundaryCandidate} · ${sampling.reason}"
                            )
                            (sampling.fallback ?: policy.error)?.let { appendLine(it) }
                            appendLine(
                                "GNSS durably ACKed · ${queues.deliveredGnss} · pending ${queues.gnss}"
                            )
                            appendLine(
                                "Storage free · ${filesDir.usableSpace / (1024 * 1024)} MiB · ${if (filesDir.usableSpace < 64L * 1024 * 1024) "LOW — new durable capture at risk" else "no automatic deletion"}"
                            )
                            appendLine(
                                "GNSS observations: ${state.observationSequence} · known collection loss: ${state.knownCollectionLoss}"
                            )
                            val collection = app.observationPersistence.state
                            appendLine(
                                "Persistence: ${collection.awaitingCommit} awaiting commit · ${collection.writeFailures} failures"
                            )
                            val transfer = app.sender.syncProgress
                            appendLine(
                                "This process: ${transfer.attemptedBytes} payload bytes attempted · ${transfer.ackedBytes} bytes durably ACKed · last batch ${transfer.lastBatchRecords} records"
                            )
                            transfer.lastError?.let { appendLine("Sync retry: $it") }
                            appendLine(
                                "History sync: ${if (op.historyPaused) "Paused" else app.sender.syncProgress.mode}"
                            )
                            appendLine(
                                "Oldest pending envelope capture: ${oldestPending ?: "None"}"
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
                                else "Tracking stopped — saved history can sync without collection"
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
                "Location collection will stop. Saved pending messages remain on this phone and can synchronize while the app is open, without restarting GNSS."
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
                        app.commandControl.changeSettings {
                            app.repository.settings(id, name, url, seconds, reenroll)
                        }
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
        historyProgressStartDelivered?.let { outState.putLong("historyProgressStartDelivered", it) }
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
                        app.repository.dao.commandHistory(),
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
