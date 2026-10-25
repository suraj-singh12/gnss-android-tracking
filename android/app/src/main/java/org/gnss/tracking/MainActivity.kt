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
import android.view.ViewGroup
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
    private lateinit var sosHistoryPage: LinearLayout
    private lateinit var sosDetailPage: LinearLayout
    private var sosRows = emptyList<Outbound>()
    private var sosPage = 0
    private var selectedSos: String? = null
    private var renderedSosEvidence: List<Any?>? = null
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
    private var route = "Tracking"
    private val routeParents = linkedMapOf<String, String>()
    private var navigateTo: ((String) -> Unit)? = null
    private var headerThemeToggle: View? = null
    private var selectedInstruction: String? = null
    private lateinit var identityName: TextView
    private lateinit var identityMeta: TextView
    private lateinit var identityBadge: TextView
    private lateinit var pendingMetric: TextView
    private lateinit var deliveredMetric: TextView
    private lateinit var lastAckLabel: TextView
    private lateinit var fixAccuracy: TextView
    private lateinit var fixAge: TextView
    private lateinit var partySummary: TextView
    private lateinit var connectionSummary: TextView
    private lateinit var partyAck: TextView
    private lateinit var partyAuthenticated: TextView
    private var renderedAlerts: List<CommandRecord>? = null
    private lateinit var policyLocal: TextView
    private lateinit var policyApplied: TextView
    private lateinit var policyRequested: TextView
    private lateinit var pendingDetail: TextView
    private lateinit var deliveredDetail: TextView
    private lateinit var unresolvedDetail: TextView
    private lateinit var ackDetail: TextView
    private lateinit var locationReadiness: TextView
    private lateinit var notificationReadiness: TextView
    private lateinit var batteryReadiness: TextView
    private lateinit var serviceReadiness: TextView
    private lateinit var changeCurrent: TextView
    private lateinit var status: TextView
    private lateinit var diagnosticsSummary: TextView
    private lateinit var settingsFeedback: TextView
    private lateinit var commandTrust: TextView
    private lateinit var commandTrustEvidence: TextView
    private lateinit var reportingPolicy: TextView
    private lateinit var reportingDetails: TextView
    private lateinit var healthHeadline: TextView
    private val healthRows = linkedMapOf<String, HealthRow>()
    private lateinit var diagnosticTrust: TextView
    private lateinit var phoneAlertSummary: TextView
    private lateinit var phoneAlertList: LinearLayout
    private lateinit var trackingContext: TextView
    private var savedInstallation: Installation? = null
    private val sectionBodies = linkedMapOf<String, LinearLayout>()
    private var draftState: Bundle? = null
    private var settingsDraftLoaded = false
    private lateinit var instructionFilter: EditText
    private var instructionPage = 0
    private var refreshConsole: (() -> Unit)? = null
    private var refreshTrust: (() -> Unit)? = null
    private val consoleFilters = linkedMapOf<String, Spinner>()
    private var consumedNotificationAction = false
    private var initialized = false
    private lateinit var syncNowButton: Button
    private lateinit var trackingActions: LinearLayout
    private lateinit var trackingAlerts: LinearLayout
    private var activePhoneAlerts = false
    private var showTracking: (() -> Unit)? = null
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

    private fun styleQuiet(button: Button) {
        button.isAllCaps = false
        button.textSize = 14f
        button.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        button.setPadding(dp(4), dp(10), dp(4), dp(10))
        button.setTextColor(getColor(R.color.accent))
        button.background =
            android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(getColor(R.color.ripple)),
                null,
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
        consumedNotificationAction =
            savedInstanceState?.getBoolean("consumedNotificationAction") ?: false
        if (
            consumedNotificationAction &&
                intent.action in listOf(TrackingNotification.SYNC, TrackingNotification.STOP)
        )
            setIntent(Intent(intent).setAction(null))
        draftState = savedInstanceState?.getBundle("settingsDraft")
        selectedSos = savedInstanceState?.getString("selectedSos")
        sosPage = savedInstanceState?.getInt("sosPage") ?: 0
        instructionPage = savedInstanceState?.getInt("instructionPage") ?: 0
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
        val routes = pages.toMutableMap()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages.values.forEach { pageContainer.addView(it) }
        scroll.addView(pageContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navigation)
        val tabButtons = mutableMapOf<String, Button>()
        fun navigate(name: String) {
            if (::sosHold.isInitialized) sosHold.cancel()
            if (route != name) {
                currentFocus?.let { focused ->
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(focused.windowToken, 0)
                    focused.clearFocus()
                }
            }
            route = name
            var top = name
            while (routeParents.containsKey(top)) top = routeParents.getValue(top)
            destination = top
            headerThemeToggle?.visibility =
                if (destination == "Tracking") View.VISIBLE else View.GONE
            if (::emergencyDock.isInitialized)
                emergencyDock.visibility =
                    if (destination == "Tracking") View.VISIBLE else View.GONE
            if (::trackingActions.isInitialized)
                trackingActions.visibility =
                    if (destination == "Tracking") View.VISIBLE else View.GONE
            routes.forEach { (key, page) ->
                page.visibility = if (key == name) View.VISIBLE else View.GONE
            }
            tabButtons.forEach { (key, tab) ->
                tab.isSelected = key == destination
                tab.setTextColor(
                    if (key == destination) getColor(R.color.accent) else getColor(R.color.muted)
                )
                tab.contentDescription = "$key${if (key == destination) ", selected" else ""}"
            }
            scroll.scrollTo(0, 0)
            if (destination == "Diagnostics") refreshConsole?.invoke()
            if (name == "Physical Button Test") refreshPhysicalButtonHistory()
            refreshTrackingBanners()
        }
        navigateTo = ::navigate
        selectedInstruction = savedInstanceState?.getString("selectedInstruction")
        instructionFullExpanded = savedInstanceState?.getBoolean("instructionFullExpanded") ?: false
        showTracking = { navigate("Tracking") }
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
            val heading =
                text(label, 13f).apply {
                    setTextColor(getColor(R.color.muted))
                    setPadding(0, dp(8), 0, 0)
                }
            return EditText(this).apply {
                id = View.generateViewId()
                heading.labelFor = id
                setSingleLine(true)
                isEnabled = false // Wait for a saved form or an explicit restored draft.
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
                if (content === pages.getValue("Tracking")) styleSecondary(this)
                else styleQuiet(this)
                setOnClickListener { action() }
                content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        fun group(
            title: String,
            @Suppress("UNUSED_PARAMETER") expanded: Boolean = true,
        ): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                content.addView(this)
                sectionBodies[title] = this
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
        val trackingTitle = text("GNSS Tracker", 24f)
        content.removeView(trackingTitle)
        content.addView(
            LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(trackingTitle, LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    Button(this@MainActivity).apply {
                        id = R.id.tracking_appearance
                        text = if (night) "☀" else "☾"
                        contentDescription =
                            if (night) "Switch to Day mode" else "Switch to Night mode"
                        styleQuiet(this)
                        textSize = 24f
                        gravity = android.view.Gravity.CENTER
                        minHeight = dp(48)
                        minWidth = dp(48)
                        setOnClickListener {
                            getSharedPreferences("appearance", MODE_PRIVATE)
                                .edit()
                                .putBoolean("night", !night)
                                .apply()
                            NotificationAppearance.refresh(this@MainActivity)
                            recreate()
                        }
                    },
                    LinearLayout.LayoutParams(dp(48), -2),
                )
            },
            0,
        )
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
            button("HOLD FOR SOS") {
                Toast.makeText(
                        this,
                        "Hold continuously for 1.2 seconds. Release to cancel. Accessibility: use the long-click action.",
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        sosButton.setTextColor(android.graphics.Color.WHITE)
        sosButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.emergency_action))
        sosButton.minHeight = dp(48)
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
                setBackgroundColor(getColor(R.color.emergency_surface))
                sosStatus.setTextColor(getColor(R.color.danger))
                addView(sosStatus, LinearLayout.LayoutParams(-1, -2))
                addView(sosButton, LinearLayout.LayoutParams(-1, -2))
                addView(holdProgress, LinearLayout.LayoutParams(-1, dp(4)))
            }
        root.addView(emergencyDock, root.childCount - 1)
        content = pages.getValue("Settings")
        text("Settings", 24f)
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
        text("Three presses within 1.5 s. Foreground only; unavailable when locked.")
        content = pages.getValue("Diagnostics")
        text("Diagnostics", 24f)
        healthHeadline =
            text("Checking phone…", 14f).apply { setTextColor(getColor(R.color.muted)) }
        val healthPanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(getColor(R.color.surface))
                        cornerRadius = dp(12).toFloat()
                    }
            }
        content.addView(healthPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        for ((name, icon) in
            listOf(
                "GNSS" to android.R.drawable.ic_menu_mylocation,
                "Storage" to android.R.drawable.ic_menu_save,
                "Command" to android.R.drawable.ic_menu_send,
                "Sync" to android.R.drawable.stat_notify_sync,
                "SOS" to android.R.drawable.ic_dialog_alert,
                "Device space" to android.R.drawable.ic_menu_manage,
                "Remote control" to android.R.drawable.ic_lock_lock,
            )) {
            healthRows[name] = HealthRow(this, name, icon).also { healthPanel.addView(it) }
        }
        content = group("Health details", false)
        diagnosticsSummary = text("Loading…", 14f)
        diagnosticTrust = text("", 14f)
        content = pages.getValue("Diagnostics")
        content = group("Phone Alerts", false)
        phoneAlertSummary = text("Loading…", 14f)
        phoneAlertList =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                content.addView(this)
            }
        button("Notification settings") {
            openSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        content = pages.getValue("Diagnostics")
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
                            relevant == null -> "Hold 1.2 s · Release to cancel"
                            pending > 0 -> "SOS pending · $pending awaiting receipt"
                            else -> "SOS received · Operator ACK at Command"
                        }
                    // A latest save failure or debounce result must not be hidden
                    // merely because an older SOS is still queued or received.
                    val currentNotice =
                        when {
                            notice?.startsWith("SOS NOT SAVED") == true ->
                                "SOS NOT SAVED · Retry / seek help"
                            notice?.startsWith("SOS trigger detected") == true ->
                                "Saving SOS on phone…"
                            notice?.startsWith("Repeated trigger ignored") == true ->
                                "Repeat ignored · Existing SOS retained"
                            notice?.startsWith("Cannot restore saved SOS") == true ->
                                "SOS recovery failed · Check storage"
                            notice?.startsWith("Saved delivery unavailable") == true ->
                                "Delivery unavailable · Check storage"
                            else -> null
                        }
                    if (currentNotice != null) sosStatus.text = currentNotice
                    sosStatus.contentDescription =
                        listOfNotNull(
                                notice,
                                sosStatus.text.toString(),
                                "$pending awaiting Command receipt",
                            )
                            .joinToString(". ")
                    renderSosHistory(rows)
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
        val phoneAlertActions =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = NativeUi(this@MainActivity).surface(R.color.emergency_surface)
            }
        trackingAlerts = phoneAlertActions
        content.addView(phoneAlertActions)
        scope.launch {
            app.repository.dao.observeCommands().collect { rows ->
                phoneAlertActions.removeAllViews()
                val active =
                    rows.filter {
                        it.trusted &&
                            it.operation == "alert" &&
                            it.state in listOf("output_attempted", "alert_blocked", "unknown")
                    }
                activePhoneAlerts = active.isNotEmpty()
                val alertUi = NativeUi(this@MainActivity)
                for (row in active) {
                    val spec =
                        runCatching { PhoneAlertSpec.parse(Protocol.parse(row.requested)) }
                            .getOrNull()
                    val heading = if (spec?.mode == "urgent") "Urgent alert" else "Command alert"
                    val state =
                        if (row.state == "unknown") " · Output unconfirmed"
                        else if (row.state == "alert_blocked") " · Output blocked" else ""
                    alertUi.add(
                        phoneAlertActions,
                        alertUi.label(
                            "$heading$state · ${spec?.message.orEmpty()}",
                            13f,
                            bold = true,
                        ),
                    )
                    alertUi.add(
                        phoneAlertActions,
                        alertUi
                            .button("Acknowledge / Stop Alert") {
                                app.workScope.launch { app.acknowledgePhoneAlert(row.id) }
                            }
                            .apply {
                                backgroundTintList =
                                    android.content.res.ColorStateList.valueOf(
                                        getColor(R.color.emergency_action)
                                    )
                                setTextColor(android.graphics.Color.WHITE)
                            },
                    )
                }
                refreshTrackingBanners()
            }
        }
        syncNowButton =
            button("Sync Now") {
                // A new manual drain gets its own progress baseline. Repeated presses
                // preserve the in-progress baseline while a drain is already active.
                val op = app.operational.value
                if (!app.sender.aggressive && !op.drainStarting && !op.historyPaused)
                    historyProgressStartDelivered = currentQueueCounts?.deliveredGnss?.toLong()
                app.syncAllNow()
                if (!app.operational.value.tracking) {
                    try {
                        startForegroundService(Intent(this, RetainedTransferService::class.java))
                    } catch (e: RuntimeException) {
                        app.operational.update {
                            it.copy(
                                error = "Background transfer unavailable; keep this app visible"
                            )
                        }
                    }
                }
            }
        content = pages.getValue("Settings")
        val fieldSettings = group("Field readiness", false)
        content = fieldSettings
        text("Android may limit background operation.", 13f)
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
        val connectionSettings = group("Party & Command")
        content = connectionSettings
        partyId = edit("Party ID")
        partyName = edit("Party name")
        val identityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((index, field) in listOf(partyId, partyName).withIndex()) {
            val heading =
                (0 until connectionSettings.childCount)
                    .map { connectionSettings.getChildAt(it) }
                    .filterIsInstance<TextView>()
                    .single { it.labelFor == field.id }
            connectionSettings.removeView(heading)
            connectionSettings.removeView(field)
            identityRow.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(heading, LinearLayout.LayoutParams(-1, -2))
                    addView(field, LinearLayout.LayoutParams(-1, -2))
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 1) leftMargin = dp(16) },
            )
        }
        connectionSettings.addView(identityRow, 0)
        commandTrust = text("Checking access…", 13f).apply { setTextColor(getColor(R.color.muted)) }
        endpoint = edit("Command address")
        endpoint.hint = "http://192.168.1.10:8080"

        interval = edit("Reporting interval (s)", true).apply { hint = "5–86400 · steps of 5" }
        val formActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val saveSettingsButton =
            button("Save settings") { save() }
                .apply {
                    gravity = android.view.Gravity.CENTER
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xff17654c.toInt())
                    setTextColor(android.graphics.Color.WHITE)
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(0xff17654c.toInt())
                            cornerRadius = dp(8).toFloat()
                        }
                }
        val cancelEditsButton =
            button("Cancel") {
                val saved = savedInstallation
                if (saved == null) {
                    settingsFeedback.text = "Storage unavailable · edits retained"
                } else {
                    restoreSettings(saved)
                    settingsFeedback.text = "Edits canceled"
                }
            }
        content.removeView(saveSettingsButton)
        content.removeView(cancelEditsButton)
        formActions.addView(saveSettingsButton, LinearLayout.LayoutParams(0, dp(48), 2f))
        formActions.addView(
            cancelEditsButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(12) },
        )
        content.addView(formActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        settingsFeedback =
            text("Local changes only", 13f).apply {
                setTextColor(getColor(R.color.muted))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        val settingsPage = pages.getValue("Settings")
        content = settingsPage
        val appearanceButton =
            button("${if (night) "Night" else "Day"}  ⌄") {
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
                            NotificationAppearance.refresh(this@MainActivity)
                            recreate()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        appearanceButton.id = R.id.appearance
        appearanceButton.contentDescription = "Appearance, ${if (night) "Night" else "Day"}"
        content = settingsPage
        val trackingPolicy = group("Tracking policy")
        content = trackingPolicy
        reportingPolicy = text("Loading…", 14f)
        content = trackingPolicy
        val adaptive =
            android.widget.Switch(this).apply {
                text = "Adaptive storage"
                minHeight = dp(48)
                isChecked = app.persistencePolicy.state.enabled
                content.addView(this)
            }
        text("Applies immediately to future observations.", 13f).apply {
            setTextColor(getColor(R.color.muted))
        }
        val policyStatus =
            text(
                if (app.persistencePolicy.state.enabled) "ON · Adaptive storage"
                else "OFF · Dense storage",
                13f,
            )
        content = group("Policy details", false)
        reportingDetails = text("Loading…", 14f)
        content = trackingPolicy
        var renderingAdaptive = false
        fun renderAdaptive() {
            val pending = app.pendingAdaptive
            val policy = app.persistencePolicy.state
            renderingAdaptive = true
            try {
                val checked = pending ?: policy.enabled
                if (adaptive.isChecked != checked) adaptive.isChecked = checked
            } finally {
                renderingAdaptive = false
            }
            val fallback = app.observationPersistence.samplingState.fallback ?: policy.error
            val status = when {
                pending != null -> "Saving · ${if (pending) "Adaptive ON" else "Dense OFF"}"
                app.adaptiveSaveFailed -> "Not saved · Dense fallback · check storage · saved ${if (policy.enabled) "ON" else "OFF"}"
                fallback != null -> "Dense fallback · $fallback"
                policy.enabled -> "ON · Adaptive storage"
                else -> "OFF · Dense storage"
            }
            if (policyStatus.text.toString() != status) policyStatus.text = status
        }
        adaptive.setOnCheckedChangeListener { _, enabled ->
            if (!renderingAdaptive) {
                app.requestAdaptive(enabled)
                renderAdaptive()
            }
        }
        renderAdaptive()
        scope.launch { app.adaptiveRequests.collect { renderAdaptive() } }

        content = pages.getValue("Diagnostics")
        val diagnosticTools =
            LinearLayout(this).apply {
                id = R.id.diagnostic_tools
                orientation = LinearLayout.VERTICAL
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        content = diagnosticTools

        button("Physical Button Test") { navigate("Physical Button Test") }
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
            text = "Foreground keys only. Locked/background keys: Mechanism unavailable."
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            physicalButtonPanel.addView(this)
        }
        TextView(this).apply {
            text = "SOS shortcut paused during test. Saved SOS delivery continues."
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
        val retryMessagesButton =
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
        content = settingsPage
        val enrollmentSettings = group("Command access", false)
        content = enrollmentSettings
        commandTrustEvidence = text("Loading trust evidence…", 14f)
        content = enrollmentSettings

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
                hint = "Command credential"
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                minHeight = dp(48)
                contentDescription = hint
                setSaveEnabled(false)
            }
        content.addView(commandCredential)
        val enrollmentStatus = text("Verify phone identity at Command first.", 13f)
        button("Authorize Command") {
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
            AlertDialog.Builder(this)
                .setTitle("Disable remote control?")
                .setMessage(
                    "New remote instructions will be unavailable. Pending results are retained."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disable") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) { app.commandControl.disable() }
                        enrollmentStatus.text = "Remote control disabled · Results retained"
                    }
                }
                .show()
        }
        content = pages.getValue("Diagnostics")
        val commandGroup = group("Command Instructions", false)
        content = commandGroup
        val commandFilter =
            EditText(this).apply {
                hint = "Search instructions"
                contentDescription = hint
            }
        instructionFilter = commandFilter
        commandFilter.setText(savedInstanceState?.getString("instructionFilter") ?: "")
        commandFilter.minHeight = dp(48)
        content.addView(commandFilter)
        content = group("Filters", false)
        for ((label, options) in
            listOf(
                "Command type" to
                    arrayOf(
                        "All types",
                        "reporting",
                        "adaptive",
                        "sync",
                        "tracking_start",
                        "tracking_stop",
                        "alert",
                    ),
                "Command outcome" to
                    arrayOf(
                        "All outcomes",
                        "received",
                        "applied",
                        "blocked",
                        "rejected",
                        "expired",
                        "duplicate",
                        "unknown",
                        "output_attempted",
                        "alert_blocked",
                        "recipient_acknowledged",
                        "cancelled",
                        "sync_partial",
                        "sync_complete",
                        "failed",
                        "superseded",
                        "unsupported",
                    ),
                "Received time" to arrayOf("All retained time", "Last 24 hours", "Last 7 days"),
            )) {
            val heading = text(label, 14f)
            val choice =
                Spinner(this).apply {
                    id = View.generateViewId()
                    minimumHeight = dp(48)
                    contentDescription = label
                    adapter =
                        object :
                            ArrayAdapter<String>(
                                this@MainActivity,
                                android.R.layout.simple_spinner_dropdown_item,
                                options,
                            ) {
                            private fun display(position: Int, view: View): View {
                                (view as TextView).text =
                                    when {
                                        position == 0 -> options[position]
                                        label == "Command type" ->
                                            commandTypeLabel(options[position])
                                        label == "Command outcome" ->
                                            commandStateLabel(options[position])
                                        else -> options[position]
                                    }
                                return view
                            }

                            override fun getView(
                                position: Int,
                                convertView: View?,
                                parent: ViewGroup,
                            ): View =
                                display(position, super.getView(position, convertView, parent))

                            override fun getDropDownView(
                                position: Int,
                                convertView: View?,
                                parent: ViewGroup,
                            ): View =
                                display(
                                    position,
                                    super.getDropDownView(position, convertView, parent),
                                )
                        }
                    setSelection((savedInstanceState?.getInt(label) ?: 0).coerceIn(options.indices))
                }
            heading.labelFor = choice.id
            content.addView(choice, LinearLayout.LayoutParams(-1, -2))
            consoleFilters[label] = choice
        }
        content = commandGroup
        val instructionSummary =
            text("Loading…", 13f).apply { setTextColor(getColor(R.color.muted)) }
        val commandRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(commandRows)
        var commandHistory = emptyList<CommandRecord>()
        var commandHistoryLoaded = false
        var renderedWindow: List<CommandRecord>? = null
        var renderedSelection: List<Any>? = null

        fun renderCommands() {
            if (
                !commandHistoryLoaded ||
                    route !in listOf("Command Instructions", "Instruction filters")
            )
                return
            val filter = commandFilter.text.toString().lowercase()
            val matching =
                commandHistory.filter {
                    val type = consoleFilters.getValue("Command type")
                    val outcome = consoleFilters.getValue("Command outcome")
                    val time = consoleFilters.getValue("Received time").selectedItemPosition
                    val since =
                        when (time) {
                            1 -> SystemClock.wallMillis() - 86400000L
                            2 -> SystemClock.wallMillis() - 7 * 86400000L
                            else -> Long.MIN_VALUE
                        }
                    (type.selectedItemPosition == 0 ||
                        it.operation == type.selectedItem.toString()) &&
                        (outcome.selectedItemPosition == 0 ||
                            it.state == outcome.selectedItem.toString() ||
                            (outcome.selectedItem.toString() == "duplicate" &&
                                it.events.contains("Duplicate ignored"))) &&
                        it.received >= since &&
                        (filter.isEmpty() ||
                            (it.id +
                                    it.operation +
                                    it.state +
                                    it.reason +
                                    utc(it.received) +
                                    it.events)
                                .lowercase()
                                .contains(filter))
                }
            val pageCount = maxOf(1, (matching.size + 19) / 20)
            instructionPage = instructionPage.coerceIn(0, pageCount - 1)
            instructionSummary.text =
                "${matching.size} matching · Page ${instructionPage + 1} of $pageCount"
            val window = matching.drop(instructionPage * 20).take(20)
            val selection =
                listOf(filter, instructionPage, pageCount, matching.size) +
                    consoleFilters.values.map { it.selectedItemPosition }
            // Authenticated contact ticks are independent of row changes. Keep
            // existing native controls/focus while their canonical data is unchanged.
            if (renderedWindow == window && renderedSelection == selection) return
            renderedWindow = window
            renderedSelection = selection
            commandRows.removeAllViews()
            for (row in window) {
                val title =
                    Button(this).apply {
                        isAllCaps = false
                        minHeight = dp(48)
                        styleQuiet(this)
                        setTextColor(getColor(R.color.ink))
                        text =
                            "${commandTypeLabel(row.operation)} · ${commandStateLabel(row.state)}   ›\n${shortCommandTime(row.received)}"
                        textSize = 14f
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        background = NativeUi(this@MainActivity).surface()
                    }
                commandRows.addView(
                    title,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
                )
                title.contentDescription = "${title.text}. Open instruction details"
                title.setOnClickListener { showInstruction(row) }
                if (
                    row.operation == "alert" &&
                        row.trusted &&
                        row.state in listOf("output_attempted", "alert_blocked", "unknown")
                ) {
                    commandRows.addView(
                        Button(this).apply {
                            text = "Acknowledge / Stop Alert"
                            minHeight = dp(48)
                            isAllCaps = false
                            styleSecondary(this)
                            setTextColor(getColor(R.color.danger))
                            setOnClickListener {
                                app.workScope.launch { app.acknowledgePhoneAlert(row.id) }
                            }
                        }
                    )
                }
            }
            if (matching.size > 20) {
                fun pageAction(label: String, enabled: Boolean, delta: Int) {
                    commandRows.addView(
                        Button(this).apply {
                            text = label
                            minHeight = dp(48)
                            isEnabled = enabled
                            isAllCaps = false
                            styleSecondary(this)
                            setOnClickListener {
                                instructionPage += delta
                                renderCommands()
                            }
                        },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                }
                pageAction("Newer instructions", instructionPage > 0, -1)
                pageAction("Older instructions", instructionPage < pageCount - 1, 1)
            }
            if (matching.isEmpty())
                commandRows.addView(
                    TextView(this).apply { text = "No received instructions match this filter" }
                )
        }
        refreshConsole = ::renderCommands
        commandFilter.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    instructionPage = 0
                    renderCommands()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        consoleFilters.values.forEach { choice ->
            var previousSelection = choice.selectedItemPosition
            choice.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (position != previousSelection) {
                            previousSelection = position
                            instructionPage = 0
                        }
                        renderCommands()
                    }
                }
        }
        refreshTrust = {
            savedInstallation?.let { state ->
                reportingPolicy.text =
                    "Applied · ${state.config().effective_reporting_interval_s} s\nCommand · ${reportingRequestSummary(state, commandHistory)}"
                reportingDetails.text =
                    reportingPolicyDescription(state, commandHistory) +
                        "\nAdaptive · ${if (app.persistencePolicy.state.enabled) "ON" else "OFF"} · revision ${app.persistencePolicy.state.revision}\nHistorical observations are unchanged."
            }
            val alerts = commandHistory.filter { it.operation == "alert" }
            phoneAlertSummary.text =
                if (alerts.isEmpty()) "No alerts received" else "${alerts.size} recorded alerts"
            if (renderedAlerts != alerts) {
                renderedAlerts = alerts
                phoneAlertList.removeAllViews()
                val alertUi = NativeUi(this)
                for (alert in alerts.take(20)) {
                    alertUi.menu(
                        phoneAlertList,
                        commandStateLabel(alert.state),
                        "${shortCommandTime(alert.received)} · Result ACK ${if (alert.confirmed) "confirmed" else "pending"}",
                    ) {
                        showInstruction(alert)
                    }
                }
            }
            if (::partyAuthenticated.isInitialized)
                partyAuthenticated.text =
                    "Authenticated contact · " +
                        if (app.commandControl.availability.startsWith("Authenticated Command"))
                            app.commandControl.availability.substringAfter(" · ")
                        else "Unknown"
            commandTrustEvidence.text =
                commandTrustDescription(
                    savedInstallation,
                    commandHistory,
                    app.commandControl.availability,
                )
            val binding = savedInstallation
            val trustState =
                when {
                    binding == null -> "Unknown"
                    binding.controlEnrollment == null -> "Unenrolled"
                    app.commandControl.availability.startsWith("Authenticated Command") ->
                        "Verified"
                    else -> "Unverified"
                }
            commandTrust.text = "Command access · $trustState"
            diagnosticTrust.text = commandTrustEvidence.text
            healthRows
                .getValue("Remote control")
                .update(
                    trustState,
                    if (binding?.controlEnrollment == null) "Enrollment required"
                    else "Authority bound",
                    if (trustState == "Verified") R.color.accent else R.color.muted,
                )
        }
        scope.launch {
            try {
                combine(app.repository.dao.observeCommands(), app.commandControl.updates) { rows, _
                        ->
                        rows
                    }
                    .collect {
                        commandHistory =
                            withContext(Dispatchers.Default) {
                                it.sortedByDescending { row -> row.received }
                            }
                        commandHistoryLoaded = true
                        refreshTrust?.invoke()
                        renderCommands()
                        if (route == "Instruction details")
                            commandHistory
                                .firstOrNull { row -> row.id == selectedInstruction }
                                ?.let { showInstruction(it, false) }
                    }
            } catch (_: Exception) {
                instructionSummary.text =
                    "Command Instructions unavailable: cannot read durable journal; check storage"
                phoneAlertSummary.text =
                    "Alert history unavailable · journal could not be read; no output or acknowledgement inferred."
            }
        }
        content = diagnosticTools
        val exportDiagnosticsButton =
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
        exportStatus = text("Sanitized ZIP · tracking continues", 13f)
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
        draftState?.let { draft ->
            restoreSettings(null, draft)
            settingsDraftLoaded = true
            draftState = null
            settingsFeedback.text = "Unsaved changes restored"
        }
        installReferenceScreens(
            root,
            navigation,
            pageContainer,
            routes,
            tabButtons,
            enrollmentSettings,
            commandGroup,
            phoneAlertActions,
            saveSettingsButton,
            cancelEditsButton,
            appearanceButton,
            adaptive,
            policyStatus,
            retryMessagesButton,
            exportDiagnosticsButton,
            ::navigate,
        )
        navigate(
            savedInstanceState?.getString("route")
                ?: savedInstanceState?.getString("destination")
                ?: "Tracking"
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) { app.repository.state() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.cannot_load_settings, e.message)
                healthHeadline.text = "Storage unavailable"
                healthRows.values.forEach { it.update("Unknown") }
                healthRows
                    .getValue("Storage")
                    .update("Unavailable", "Check device space", R.color.warning)
                diagnosticsSummary.text = "Storage unavailable · health and queues unknown."
                settingsFeedback.text =
                    "Settings unavailable · local persistence could not be read."
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
                ) { state, queues, blockedError, _, localPolicy ->
                    if (state != null) {
                        val oldestPending =
                            withContext(Dispatchers.IO) {
                                app.repository.dao.oldestPendingCapturedMillis()?.let { utc(it) }
                            }
                        // The diagnostic query suspends. Snapshot current operational state once
                        // afterwards, so the fix, Tracking pill and controls describe one instant.
                        val op = app.operational.value
                        savedInstallation = state
                        refreshTrust?.invoke()

                        if (!initialized) {
                            if (!settingsDraftLoaded) restoreSettings(state)
                            settingsDraftLoaded = true
                            initialized = true
                            updateStartControls(op)
                            dispatchNotificationAction()
                            attemptStart()
                        }
                        identityName.text = state.partyName.ifBlank { "Phone not named" }
                        identityMeta.text = state.partyId.ifBlank { "Party not configured" }
                        identityBadge.text =
                            state.partyName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                        partySummary.text =
                            state.partyName.ifBlank { "Not configured" } + " · " + state.partyId
                        connectionSummary.text = state.endpoint.ifBlank { "Not configured" }
                        changeCurrent.text = state.endpoint.ifBlank { "Not configured" }
                        serviceReadiness.text =
                            if (op.tracking) "Foreground tracking active"
                            else "Start from the app · Android limits apply"
                        partyAck.text =
                            "Message ACK · ${state.lastAck?.let { shortUtcTime(it) } ?: "None"}"
                        policyLocal.text = "${state.localInterval} s"
                        policyApplied.text = "${state.config().effective_reporting_interval_s} s"
                        policyRequested.text = reportingRequestSummary(state, commandHistory)
                        trackingSummary.text =
                            when {
                                op.starting -> "Starting…"
                                op.stopping -> "Stopping…"
                                op.tracking -> "Tracking on"
                                else -> "Tracking off"
                            }
                        NativeUi(this@MainActivity).pill(trackingSummary, op.tracking)
                        trackingError.text = op.error ?: state.operationalError ?: ""
                        trackingError.visibility =
                            if (trackingError.text.isEmpty()) View.GONE else View.VISIBLE
                        gnssSummary.text =
                            when {
                                !op.tracking ->
                                    if (op.ageMillis != null) "Last known fix" else "No current fix"
                                op.gnss == "fix" &&
                                    op.ageMillis != null &&
                                    op.ageMillis in 0..30000 -> "Fresh fix"
                                op.ageMillis != null -> "Stale fix"
                                else -> "No fix"
                            }
                        fixAccuracy.text =
                            op.accuracy?.let { "± %.1f m".format(java.util.Locale.ROOT, it) }
                                ?: "— m"
                        fixAge.text =
                            when {
                                !op.tracking ->
                                    op.ageMillis?.let { "At last update · ${it / 1000} s" }
                                        ?: "Age unavailable · Tracking off"
                                else ->
                                    op.ageMillis?.let { "${it / 1000} s old" } ?: "Waiting for GNSS"
                            }
                        // Tracking state has its own pill; fix age never changes the card height.
                        val connection =
                            if (app.sender.commandReachable == true) "Command Connected"
                            else "Offline"
                        commandSummary.text =
                            if (app.sender.commandReachable == true) "Command reachable"
                            else "Command offline"
                        NativeUi(this@MainActivity)
                            .pill(commandSummary, app.sender.commandReachable == true)
                        pendingMetric.text = queues.gnss.toString()
                        deliveredMetric.text = queues.deliveredGnss.toString()
                        pendingDetail.text = queues.gnss.toString()
                        deliveredDetail.text = queues.deliveredGnss.toString()
                        unresolvedDetail.text = queues.blocked.toString()
                        lastAckLabel.text =
                            "Last ACK · ${state.lastAck?.let { shortUtcTime(it) } ?: "None"}"
                        ackDetail.text = state.lastAck ?: "No message ACK recorded"
                        trackingContext.text =
                            when {
                                queues.sos > 0 -> "SOS pending · Waiting for Command receipt"
                                app.sender.commandReachable != true ->
                                    "Command offline · Saved data will retry"
                                else -> ""
                            }
                        trackingContext.visibility =
                            if (trackingContext.text.isEmpty()) View.GONE else View.VISIBLE
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
                            if (sync.total == 0L) "Queue clear"
                            else "${sync.percentage}% · ${sync.pending} pending"
                        historyProgressBar.contentDescription =
                            "Saved GNSS sync ${sync.percentage} percent; " +
                                "${sync.acknowledged} of ${sync.total} currently known GNSS observations acknowledged, " +
                                "${sync.pending} pending"
                        historySummary.text =
                            when {
                                queues.blocked > 0 || state.deliveryPaused ->
                                    "${queues.blocked} unresolved · Check Diagnostics"
                                queues.gnss == 0 -> "All saved GNSS acknowledged"
                                app.sender.aggressive -> "Syncing saved observations"
                                else -> "${queues.gnss} waiting to sync"
                            }
                        batterySummary.text =
                            "Battery · ${op.health.battery_percent?.let { "$it%" } ?: "Unavailable"}${if (op.health.charging == true) " · Charging" else ""}"
                        renderAdaptive()
                        val collectionHealth = app.observationPersistence.state
                        val attention =
                            when {
                                collectionHealth.writeFailures > 0 ||
                                    state.knownCollectionLoss > 0 -> "Storage needs attention"
                                queues.sos > 0 -> "SOS delivery pending"
                                queues.blocked > 0 || state.deliveryPaused ->
                                    "Delivery needs attention"
                                op.error != null || state.operationalError != null ->
                                    "Tracking needs attention"
                                else -> if (op.tracking) "Tracking active" else "Tracking stopped"
                            }
                        healthHeadline.text = attention
                        healthHeadline.setTextColor(
                            getColor(
                                if (attention.contains("attention") || queues.sos > 0)
                                    R.color.warning
                                else R.color.muted
                            )
                        )
                        val fresh =
                            op.tracking &&
                                op.gnss == "fix" &&
                                op.ageMillis != null &&
                                op.ageMillis in 0..30000
                        healthRows
                            .getValue("GNSS")
                            .update(
                                if (!op.tracking && op.ageMillis != null) "Last known"
                                else if (fresh) "Fresh fix"
                                else if (op.ageMillis != null) "Stale / unknown" else "No fix",
                                listOfNotNull(
                                        op.accuracy?.let {
                                            "%.1f m".format(java.util.Locale.ROOT, it)
                                        },
                                        op.ageMillis?.let {
                                            if (op.tracking) "${it / 1000} s old"
                                            else "${it / 1000} s at last update"
                                        },
                                    )
                                    .joinToString(" · ")
                                    .ifEmpty { "Accuracy unavailable" },
                                if (fresh) R.color.accent else R.color.muted,
                            )
                        healthRows
                            .getValue("Storage")
                            .update(
                                if (collectionHealth.writeFailures > 0) "Write error"
                                else if (collectionHealth.awaitingCommit > 0) "Writing"
                                else "Ready",
                                "${queues.gnss} queued" +
                                    if (state.knownCollectionLoss > 0)
                                        " · ${state.knownCollectionLoss} lost"
                                    else "",
                                if (
                                    collectionHealth.writeFailures > 0 ||
                                        state.knownCollectionLoss > 0
                                )
                                    R.color.warning
                                else R.color.muted,
                            )
                        healthRows
                            .getValue("Command")
                            .update(
                                if (app.sender.commandReachable == true) "Reachable"
                                else "No contact",
                                "Message ACK · ${state.lastAck?.let { shortUtcTime(it) } ?: "None"}",
                                R.color.muted,
                            )
                        healthRows
                            .getValue("Sync")
                            .update(
                                if (queues.blocked > 0 || state.deliveryPaused) "Needs attention"
                                else if (op.historyPaused) "Paused"
                                else if (queues.gnss == 0) "Queue clear" else "Pending",
                                "${queues.gnss} pending" +
                                    if (queues.blocked > 0) " · ${queues.blocked} unresolved"
                                    else "",
                                if (queues.blocked > 0 || state.deliveryPaused) R.color.warning
                                else R.color.muted,
                            )
                        healthRows
                            .getValue("SOS")
                            .update(
                                if (queues.sos > 0) "${queues.sos} pending" else "No pending",
                                "Operator ACK · Command only",
                                if (queues.sos > 0) R.color.warning else R.color.muted,
                            )
                        val freeSpace = filesDir.usableSpace
                        healthRows
                            .getValue("Device space")
                            .update(
                                if (freeSpace < 64L * 1024 * 1024) "Low" else "Available",
                                if (freeSpace >= 1024L * 1024 * 1024)
                                    "%.1f GB free"
                                        .format(
                                            java.util.Locale.ROOT,
                                            freeSpace.toDouble() / (1024L * 1024 * 1024),
                                        )
                                else "${freeSpace / (1024 * 1024)} MB free",
                                if (freeSpace < 64L * 1024 * 1024) R.color.warning
                                else R.color.muted,
                            )
                        diagnosticsSummary.text = buildString {
                            val collection = app.observationPersistence.state
                            appendLine(
                                when {
                                    collection.writeFailures > 0 || state.knownCollectionLoss > 0 ->
                                        "ATTENTION · persistence needs investigation"
                                    queues.blocked > 0 || state.deliveryPaused ->
                                        "ATTENTION · saved delivery needs recovery"
                                    queues.sos > 0 -> "ATTENTION · SOS awaiting Command receipt"
                                    op.error != null || state.operationalError != null ->
                                        "ATTENTION · ${op.error ?: state.operationalError}"
                                    else ->
                                        "Review current evidence below · Tracking ${if (op.tracking) "active" else "stopped"}"
                                }
                            )
                            appendLine()
                            appendLine(
                                "GNSS · ${if (op.tracking) "Collecting" else "Tracking stopped"}"
                            )
                            appendLine(op.fixDescription().replace("GPS:", "Fix:"))
                            appendLine()
                            appendLine(
                                "Storage · ${collection.awaitingCommit} awaiting Room commit · ${collection.writeFailures} write failures"
                            )
                            appendLine(
                                "${queues.gnss} saved GNSS queued · ${state.knownCollectionLoss} known collection loss"
                            )
                            if (collection.writeFailures > 0 || state.knownCollectionLoss > 0)
                                appendLine(
                                    "Attention: inspect storage and export evidence; queued data is retained."
                                )
                            appendLine()
                            appendLine(
                                "Command · ${if (app.sender.commandReachable == true) "Transport reachable" else "Contact unavailable or unknown"}"
                            )
                            appendLine(
                                "Last durable message ACK: ${state.lastAck ?: "None"} · authentication shown separately below"
                            )
                            appendLine()
                            appendLine(
                                "Synchronization · ${if (op.historyPaused) "Paused" else app.sender.syncProgress.mode}"
                            )
                            appendLine(
                                "${queues.deliveredGnss} GNSS ACKed · ${queues.gnss} pending · ${queues.blocked} unresolved"
                            )
                            if (queues.blocked > 0 || state.deliveryPaused)
                                appendLine(
                                    "Check Command endpoint, then Retry saved messages in Advanced Diagnostics. ${blockedError ?: ""}"
                                )
                            appendLine("Command reconstruction is separate from delivery ACK.")
                            appendLine()
                            appendLine("SOS · ${queues.sos} awaiting durable delivery")
                            appendLine(
                                "Operator acknowledgement: view Command; phone delivery is separate. See SOS event details."
                            )
                            appendLine()
                            val free = filesDir.usableSpace
                            append(
                                "Device storage · ${free / (1024 * 1024)} MiB free · ${if (free < 64L * 1024 * 1024) "LOW: free space before further capture" else "no low-space warning"}"
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
                        updateStartControls(op)
                        refreshTrackingBanners()
                    }
                }
                .catch { e ->
                    initialized = false
                    updateStartControls()
                    status.text = getString(R.string.cannot_read_settings, e.message)
                    healthHeadline.text = "Storage unavailable"
                    healthRows.values.forEach { it.update("Unknown") }
                    healthRows
                        .getValue("Storage")
                        .update("Unavailable", "Check device space", R.color.warning)
                    diagnosticsSummary.text = "Storage unavailable · health and queues unknown."
                    settingsFeedback.text = "Storage unavailable · edits retained"
                    trackingSummary.text = "ERROR"
                    trackingError.text = status.text
                    trackingError.visibility = View.VISIBLE
                }
                .collect {}
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Framework hierarchy restoration runs after onCreate. Restore the editor caret last.
        savedInstanceState?.getBundle("settingsDraft")?.let { draft ->
            val editor =
                when (draft.getString("focus")) {
                    "partyId" -> partyId
                    "partyName" -> partyName
                    "endpoint" -> endpoint
                    "interval" -> interval
                    else -> null
                }
            editor?.let {
                it.requestFocus()
                val name = draft.getString("focus")
                it.setSelection(
                    draft.getInt("${name}Selection", it.length()).coerceIn(0, it.length())
                )
            }
        }
    }

    private fun renderSosHistory(rows: List<Outbound>) {
        if (!::sosHistoryPage.isInitialized) return
        if (sosRows != rows || sosHistoryPage.childCount <= 2) {
            sosRows = rows
            while (sosHistoryPage.childCount > 2) sosHistoryPage.removeViewAt(2)
            val ui = NativeUi(this)
            val pages = maxOf(1, (rows.size + 19) / 20)
            sosPage = sosPage.coerceIn(0, pages - 1)
            if (rows.isEmpty())
                ui.add(sosHistoryPage, ui.label("No SOS events saved", 14f, muted = true), 18)
            for (row in rows.drop(sosPage * 20).take(20)) {
                ui.menu(
                    sosHistoryPage,
                    if (row.deliveredAt != null) "Command received" else "Delivery pending",
                    "${shortCommandTime(row.capturedMillis)} · ${sosReference(row.messageId)}",
                ) {
                    showSos(row)
                }
            }
            if (pages > 1) {
                ui.add(
                    sosHistoryPage,
                    ui.label("Page ${sosPage + 1} of $pages", 12f, muted = true),
                    8,
                )
                for ((label, delta) in listOf("Newer events" to -1, "Older events" to 1)) ui.add(
                    sosHistoryPage,
                    ui.button(label) {
                            sosPage += delta
                            sosRows = emptyList()
                            renderSosHistory(rows)
                        }
                        .apply { isEnabled = sosPage + delta in 0 until pages },
                    8,
                )
            }
        }
        if (route == "SOS details")
            rows.firstOrNull { it.messageId == selectedSos }?.let { showSos(it, false) }
    }

    private fun showSos(row: Outbound, navigate: Boolean = true) {
        val evidence =
            listOf(
                row,
                app.operational.value.health.wifi_connected,
                app.activityVisible || app.operational.value.tracking,
            )
        if (renderedSosEvidence == evidence) {
            if (navigate) navigateTo?.invoke("SOS details")
            return
        }
        renderedSosEvidence = evidence
        selectedSos = row.messageId
        val ui = NativeUi(this)
        sosDetailPage.removeAllViews()
        ui.add(
            sosDetailPage,
            ui.button("‹ SOS event history") { navigateTo?.invoke("SOS event history") }
                .apply { styleQuiet(this) },
        )
        ui.add(sosDetailPage, ui.label("SOS details", 22f, bold = true), 8)
        val message = Protocol.decodeMessage(row.json)
        val card = ui.card(sosDetailPage, 18, R.color.emergency_surface)
        ui.add(
            card,
            ui.label(
                if (row.deliveredAt == null) "SOS saved on phone" else "SOS received by Command",
                18f,
                bold = true,
            ),
        )
        ui.add(card, ui.label(row.messageId, 12f, muted = true).apply { setTextIsSelectable(true) })
        ui.fact(card, "Created", shortCommandTime(row.capturedMillis))
        val facts = ui.card(sosDetailPage)
        ui.fact(
            facts,
            "Location",
            message.fix?.let {
                "%.5f, %.5f".format(java.util.Locale.ROOT, it.latitude, it.longitude)
            } ?: "Unavailable",
        )
        ui.fact(
            facts,
            "Fix age at save",
            message.fix?.let { "${it.fix_age_ms / 1000} s" } ?: "Unknown",
        )
        ui.fact(
            facts,
            "Delivery",
            if (row.deliveredAt != null) "Command received"
            else if (row.quarantined) "Unresolved" else "Pending",
        )
        ui.fact(facts, "Message ACK", row.deliveredAt?.let(::shortUtcTime) ?: "Unconfirmed")
        ui.fact(facts, "Operator ACK", "View Command")
        ui.add(
            facts,
            ui.label(
                row.sosDescription(
                    app.operational.value.health.wifi_connected,
                    app.activityVisible || app.operational.value.tracking,
                ),
                12f,
                muted = true,
            ),
            8,
        )
        ui.add(sosDetailPage, ui.button("Export evidence") { exportEvidence?.invoke() }, 12)
        if (navigate) navigateTo?.invoke("SOS details")
    }

    private lateinit var instructionDetailsPage: LinearLayout
    private var exportEvidence: (() -> Unit)? = null
    private var renderedInstruction: CommandRecord? = null
    private var instructionFullExpanded = false

    private fun showInstruction(row: CommandRecord, navigate: Boolean = true) {
        if (selectedInstruction != row.id) instructionFullExpanded = false
        selectedInstruction = row.id
        if (renderedInstruction != row) {
            renderedInstruction = row
            val ui = NativeUi(this)
            instructionDetailsPage.removeAllViews()
            ui.add(
                instructionDetailsPage,
                ui.button("‹ Command Instructions") { navigateTo?.invoke("Command Instructions") }
                    .apply {
                        styleQuiet(this)
                        setTextColor(getColor(R.color.secondary))
                    },
            )
            ui.add(instructionDetailsPage, ui.label("Instruction details", 22f, bold = true), 12)
            ui.add(
                instructionDetailsPage,
                ui.label(commandStateLabel(row.state)).apply {
                    ui.pill(this, row.state == "applied")
                },
                12,
            )
            val summary = ui.card(instructionDetailsPage)
            ui.add(summary, ui.label(row.id, 12f, muted = true).apply { setTextIsSelectable(true) })
            ui.fact(summary, "Type", commandTypeLabel(row.operation))
            ui.fact(summary, "Received", shortCommandTime(row.received))
            ui.fact(summary, "Verification", if (row.trusted) "Authenticated" else "Unverified")
            ui.fact(summary, "Execution", if (row.attempted) "Attempt recorded" else "Unconfirmed")
            ui.fact(summary, "Result ACK", if (row.confirmed) "Confirmed" else "Pending")
            ui.add(
                summary,
                ui.label(row.authority, 12f, muted = true).apply { setTextIsSelectable(true) },
                8,
            )
            val stages = ui.card(instructionDetailsPage)
            ui.add(stages, ui.label("STAGES", 11f, muted = true, bold = true))
            ui.fact(stages, "Received", shortCommandTime(row.received))
            ui.fact(
                stages,
                "Attempt",
                row.attemptedAt.takeIf { it > 0 }?.let(::shortCommandTime) ?: "Unconfirmed",
            )
            ui.fact(
                stages,
                "Result",
                row.completedAt.takeIf { it > 0 }?.let(::shortCommandTime) ?: "Pending / unknown",
            )
            ui.fact(
                stages,
                "Custody",
                row.confirmedAt.takeIf { it > 0 }?.let(::shortCommandTime) ?: "Unconfirmed",
            )
            ui.add(stages, ui.label(row.reason, 13f, bold = true), 8)
            ui.add(
                stages,
                ui.label(
                    commandReasonExplanation(row.reason) ?: commandOutcomeExplanation(row.state),
                    12f,
                    muted = true,
                ),
            )
            ui.add(
                instructionDetailsPage,
                ui.button(if (row.trusted) "Copy command ID" else "Copy security event ID") {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Command ID", row.id))
                    Toast.makeText(this, "ID copied", Toast.LENGTH_SHORT).show()
                },
                12,
            )
            ui.add(
                instructionDetailsPage,
                ui.button("Export evidence") { exportEvidence?.invoke() },
                8,
            )
            if (
                row.operation == "alert" &&
                    row.trusted &&
                    row.state in listOf("output_attempted", "alert_blocked", "unknown")
            )
                ui.add(
                    instructionDetailsPage,
                    ui.button("Acknowledge / Stop Alert") {
                        app.workScope.launch { app.acknowledgePhoneAlert(row.id) }
                    },
                    8,
                )
            val full =
                ui.label(commandInstructionDetails(row), 13f, muted = true).apply {
                    setTextIsSelectable(true)
                    visibility = if (instructionFullExpanded) View.VISIBLE else View.GONE
                }
            ui.add(
                instructionDetailsPage,
                ui.button("Full record") {
                    instructionFullExpanded = !instructionFullExpanded
                    full.visibility = if (instructionFullExpanded) View.VISIBLE else View.GONE
                },
                12,
            )
            ui.add(instructionDetailsPage, full)
        }
        if (navigate) navigateTo?.invoke("Instruction details")
    }

    /** Compose the PDF's native destinations around the existing operational controls. */
    private fun installReferenceScreens(
        root: LinearLayout,
        navigation: LinearLayout,
        pageContainer: LinearLayout,
        routes: MutableMap<String, LinearLayout>,
        tabs: Map<String, Button>,
        enrollment: LinearLayout,
        console: LinearLayout,
        phoneAlertActions: LinearLayout,
        saveButton: Button,
        cancelButton: Button,
        appearance: Button,
        adaptive: Switch,
        policyStatus: TextView,
        retryButton: Button,
        exportButton: Button,
        navigate: (String) -> Unit,
    ) {
        val ui = NativeUi(this)
        fun detach(view: View) {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        fun title(page: LinearLayout, name: String) {
            val heading = ui.label(name, 22f, bold = true)
            if (Build.VERSION.SDK_INT >= 28) {
                heading.isAccessibilityHeading = true
                page.accessibilityPaneTitle = name
            }
            ui.add(page, heading, 8)
        }
        fun newPage(name: String, parent: String): LinearLayout {
            routeParents[name] = parent
            val page = ui.column().apply { setPadding(dp(20), 0, dp(20), dp(24)) }
            routes[name] = page
            pageContainer.addView(page)
            ui.add(
                page,
                ui.button("‹ $parent") { navigate(parent) }
                    .apply {
                        styleQuiet(this)
                        setTextColor(getColor(R.color.secondary))
                    },
            )
            title(page, name)
            return page
        }
        fun menu(page: LinearLayout, name: String, subtitle: String) {
            ui.menu(page, name, subtitle) { navigate(name) }
        }
        fun moveAll(from: LinearLayout, to: LinearLayout) {
            val children = (0 until from.childCount).map { from.getChildAt(it) }
            children.forEach { ui.add(to, it, 4) }
        }
        fun labeledField(parent: LinearLayout, caption: String, input: EditText) {
            val heading = ui.label(caption, 12f, muted = true).apply { labelFor = input.id }
            ui.add(parent, heading, 8)
            ui.add(parent, input)
        }
        fun formActions(page: LinearLayout) {
            ui.add(
                page,
                ui.button("Save") { saveButton.performClick() }
                    .apply { ui.style(this, primary = true) },
                16,
            )
            ui.add(
                page,
                ui.button("Cancel") {
                    cancelButton.performClick()
                    navigate(routeParents[route] ?: "Settings")
                },
                8,
            )
        }
        // Capture retained diagnostic sections before rebuilding the destination roots.
        val healthPanel = healthRows.values.first().parent as LinearLayout
        val healthDetails = sectionBodies.getValue("Health details")
        val technical = sectionBodies.getValue("Advanced technical evidence")
        val alertPanel = sectionBodies.getValue("Phone Alerts")
        val filters = sectionBodies.getValue("Filters")
        val policyDetails = sectionBodies.getValue("Policy details")
        detach(filters)
        val themeToggle =
            routes.getValue("Tracking").let { page ->
                page.findViewById<Button>(R.id.tracking_appearance)
            }
        // The shared app header never competes with the party identity or screen title.
        val header =
            LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(8), dp(12), dp(6))
                addView(
                    ui.label("GNSS Tracker", 23f, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
        headerThemeToggle = themeToggle
        header.minimumHeight = dp(62)
        detach(themeToggle)
        header.addView(themeToggle, LinearLayout.LayoutParams(dp(48), -2))
        root.addView(header, 0)
        fun addRoute(name: String, parent: String, body: LinearLayout): LinearLayout {
            val page = newPage(name, parent)
            body.visibility = View.VISIBLE
            (0 until body.childCount)
                .map { body.getChildAt(it) }
                .filterIsInstance<Button>()
                .forEach { action ->
                    ui.style(
                        action,
                        primary = action.text == "Authorize Command",
                        danger = action.text == "Disable remote control locally",
                    )
                }
            moveAll(body, page)
            return page
        }
        // Root pages consist only of the operator's next destinations.
        for (name in listOf("Settings", "Diagnostics")) routes.getValue(name).removeAllViews()
        val settings = routes.getValue("Settings")
        settings.setPadding(dp(20), 0, dp(20), dp(24))
        title(settings, "Settings")
        menu(settings, "Party & Command", "Identity, connection and trust")
        menu(settings, "Tracking & GNSS", "Reporting interval and storage policy")
        menu(settings, "Field readiness", "Permissions and battery")
        menu(settings, "Appearance & SOS", "Theme and emergency controls")
        menu(settings, "Advanced settings", "Device identity and Command access")
        val party = newPage("Party & Command", "Settings")
        val identity = ui.card(party, 18)
        ui.add(identity, ui.label("PARTY", 11f, muted = true, bold = true))
        partySummary = ui.label("Loading…", 18f, bold = true)
        ui.add(identity, partySummary)
        ui.add(identity, ui.button("Edit party") { navigate("Edit connection") }, 4)
        val connection = ui.card(party)
        ui.add(connection, ui.label("COMMAND", 11f, muted = true, bold = true))
        connectionSummary = ui.label("Loading…", 17f, bold = true)
        ui.add(connection, connectionSummary)
        ui.add(connection, commandTrust)
        partyAck = ui.label("Message ACK · Unknown", 12f, muted = true)
        partyAuthenticated = ui.label("Authenticated contact · Unknown", 12f, muted = true)
        ui.add(connection, partyAck)
        ui.add(connection, partyAuthenticated)
        ui.add(party, ui.button("Edit connection") { navigate("Edit connection") }, 16)
        ui.add(
            party,
            ui.button("Change trusted Command") { navigate("Change trusted Command") }
                .apply { ui.style(this, danger = true) },
            8,
        )
        menu(party, "Command trust", "Authority and authenticated contact")
        val editConnection = newPage("Edit connection", "Party & Command")
        val form = ui.card(editConnection, 18)
        labeledField(form, "Party ID", partyId)
        labeledField(form, "Party name", partyName)
        labeledField(form, "Command address", endpoint)
        formActions(editConnection)
        ui.add(editConnection, settingsFeedback, 8)
        val intervalFeedback = ui.label("", 13f, muted = true)
        settingsFeedback.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: android.text.Editable?) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    intervalFeedback.text = s
                }
            }
        )
        val policyPage = newPage("Tracking & GNSS", "Settings")
        val intervalCard = ui.card(policyPage, 18)
        ui.add(intervalCard, ui.label("REPORTING INTERVAL", 11f, muted = true, bold = true))
        policyLocal = ui.fact(intervalCard, "Phone interval", "Loading…")
        policyApplied = ui.fact(intervalCard, "Applied interval", "Unknown")
        policyRequested = ui.fact(intervalCard, "Command request", "Unknown")
        ui.add(intervalCard, ui.button("Change interval") { navigate("Reporting interval") }, 10)
        val intervalPage = newPage("Reporting interval", "Tracking & GNSS")
        labeledField(ui.card(intervalPage, 18), "Phone interval · seconds", interval)
        formActions(intervalPage)
        ui.add(intervalPage, intervalFeedback, 8)
        val adaptiveCard = ui.card(policyPage)
        ui.add(adaptiveCard, ui.label("GNSS STORAGE", 11f, muted = true, bold = true))
        adaptive.text = "Adaptive storage"
        adaptive.setTextColor(getColor(R.color.ink))
        adaptive.textSize = 16f
        adaptive.switchPadding = dp(16)
        // Native checked semantics plus the adjacent policy status expose ON/OFF without
        // squeezing scaled state text into Android's fixed-width switch thumb.
        adaptive.showText = false
        ui.add(adaptiveCard, adaptive, 6)
        ui.add(
            adaptiveCard,
            ui.label("Future observations only. History is unchanged.", 12f, muted = true),
            4,
        )
        ui.add(adaptiveCard, policyStatus, 4)
        addRoute("Policy details", "Tracking & GNSS", policyDetails)
        menu(policyPage, "Policy details", "Revision and requested / applied evidence")
        val readinessPage = newPage("Field readiness", "Settings")
        fun readinessCard(name: String, actions: List<Button>): TextView {
            val card = ui.card(readinessPage)
            ui.add(card, ui.label(name, 16f, bold = true))
            val value = ui.label("Checking…", 13f, muted = true)
            ui.add(card, value, 4)
            actions.forEach { action ->
                ui.style(action)
                ui.add(card, action, 8)
            }
            return value
        }
        locationReadiness = readinessCard("Location", listOf(locationAction, gpsAction))
        notificationReadiness = readinessCard("Notifications", listOf(notificationAction))
        batteryReadiness =
            readinessCard("Battery & background", listOf(saverAction, backgroundAction))
        serviceReadiness = readinessCard("Tracking service", emptyList())
        ui.add(
            readinessPage,
            ui.label("Android may limit background tracking and alerts.", 12f, muted = true),
            12,
        )
        val appearancePage = newPage("Appearance & SOS", "Settings")
        val appearanceCard = ui.card(appearancePage, 18)
        ui.add(appearanceCard, ui.label("Appearance", 16f, bold = true))
        ui.style(appearance)
        ui.add(appearanceCard, appearance, 8)
        val shortcut = ui.card(appearancePage)
        ui.add(shortcut, ui.label("Volume shortcut", 16f, bold = true))
        keyOption.text = "Enable volume-up SOS"
        keyOption.textSize = 14f
        ui.add(shortcut, keyOption, 8)
        ui.add(shortcut, ui.label("3 presses in 1.5 s · App open and unlocked", 12f, muted = true))
        val hold = ui.card(appearancePage)
        ui.fact(hold, "SOS hold", "1.2 seconds")
        ui.add(hold, ui.label("Release early to cancel.", 12f, muted = true))
        val advancedSettings = newPage("Advanced settings", "Settings")
        menu(advancedSettings, "Command access", "Authorize or disable remote control")
        menu(advancedSettings, "Command trust", "Phone identity and trust evidence")
        addRoute("Command access", "Advanced settings", enrollment)
        val trust = newPage("Command trust", "Party & Command")
        ui.add(ui.card(trust, 18), commandTrustEvidence)
        val change = newPage("Change trusted Command", "Party & Command")
        val currentCommand = ui.card(change, 18)
        ui.add(currentCommand, ui.label("CURRENT COMMAND", 11f, muted = true, bold = true))
        changeCurrent = ui.label("Loading…", 17f, bold = true)
        ui.add(currentCommand, changeCurrent)
        val proposed = ui.card(change)
        ui.add(proposed, ui.label("COMMAND TO ENROLL", 11f, muted = true, bold = true))
        val proposedAddress =
            ui.label(endpoint.text.toString().ifBlank { "Not configured" }, 17f, bold = true)
        ui.add(proposed, proposedAddress)
        endpoint.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: android.text.Editable?) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    proposedAddress.text = s?.takeIf { it.isNotBlank() } ?: "Not configured"
                }
            }
        )
        ui.add(proposed, ui.button("Edit address") { navigate("Edit connection") }, 8)
        ui.add(
            ui.card(change),
            ui.label(
                "Saved messages and phone identity are retained. Remote control must be authorized again.",
                14f,
            ),
        )
        ui.add(
            change,
            ui.button("Confirm change") {
                    AlertDialog.Builder(this)
                        .setTitle("Change trusted Command?")
                        .setMessage(
                            "Clear remote-control authorization and the reporting override? Saved observations remain on this phone."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Re-enroll") { _, _ -> save(reenroll = true) }
                        .show()
                }
                .apply { ui.style(this, danger = true) },
            16,
        )
        ui.add(change, ui.button("Cancel") { navigate("Party & Command") }, 8)
        val diagnostics = routes.getValue("Diagnostics")
        diagnostics.setPadding(dp(20), 0, dp(20), dp(24))
        title(diagnostics, "Diagnostics")
        ui.add(diagnostics, ui.label("DEVICE HEALTH", 11f, muted = true, bold = true), 18)
        ui.add(diagnostics, healthPanel, 6)
        menu(diagnostics, "Command Instructions", "Received instructions and outcomes")
        menu(diagnostics, "Phone Alerts", "Output and human acknowledgement")
        menu(diagnostics, "Saved data & sync", "Backlog, receipt and storage")
        menu(diagnostics, "Diagnostic tools", "Recovery, export and button test")
        addRoute("Command Instructions", "Diagnostics", console)
        addRoute("Instruction filters", "Command Instructions", filters)
        val consolePage = routes.getValue("Command Instructions")
        ui.add(consolePage, ui.button("Filters") { navigate("Instruction filters") })
        val chips = LinearLayout(this)
        for ((label, position) in
            listOf("All" to 0, "Applied" to 2, "Blocked" to 3, "Pending" to 1)) {
            chips.addView(
                ui.button(label) {
                        consoleFilters.getValue("Command outcome").setSelection(position)
                    }
                    .apply {
                        textSize = 12f
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                    },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) },
            )
        }
        consolePage.addView(chips, 2)
        instructionDetailsPage = newPage("Instruction details", "Command Instructions")
        addRoute("Phone Alerts", "Diagnostics", alertPanel)
        menu(
            routes.getValue("Phone Alerts"),
            "Command Instructions",
            "Open a recorded alert for details",
        )
        val savedData = newPage("Saved data & sync", "Diagnostics")
        val totals = ui.card(savedData, 18)
        ui.add(totals, ui.label("SAVED OBSERVATIONS", 11f, muted = true, bold = true))
        pendingDetail = ui.fact(totals, "Pending", "Unknown")
        deliveredDetail = ui.fact(totals, "Command ACKed", "Unknown")
        unresolvedDetail = ui.fact(totals, "Unresolved", "Unknown")
        val ack = ui.card(savedData)
        ui.add(ack, ui.label("LAST COMMAND ACK", 11f, muted = true, bold = true))
        ackDetail = ui.label("Unknown", 14f)
        ui.add(ack, ackDetail)
        ui.add(savedData, ui.button("Retry saved messages") { retryButton.performClick() }, 16)
        ui.add(
            savedData,
            ui.label("Message receipt and Command reconstruction are separate.", 12f, muted = true),
            8,
        )
        menu(savedData, "Storage health", "Room and delivery evidence")
        addRoute("Storage health", "Saved data & sync", healthDetails)
        val toolsPage =
            newPage("Diagnostic tools", "Diagnostics").apply { id = R.id.diagnostic_tools }
        ui.style(retryButton)
        ui.style(exportButton)
        ui.add(toolsPage, retryButton, 18)
        exportButton.text = "Export diagnostic ZIP"
        ui.add(toolsPage, exportButton, 8)
        ui.add(toolsPage, exportStatus, 4)
        menu(toolsPage, "Physical Button Test", "Inspect foreground key events")
        menu(toolsPage, "Advanced logs", "Technical device evidence")
        menu(toolsPage, "SOS event history", "Delivery and Command acknowledgement")
        addRoute("Advanced logs", "Diagnostic tools", technical)
        sosHistoryPage = newPage("SOS event history", "Diagnostic tools")
        sosDetailPage = newPage("SOS details", "SOS event history")
        ui.add(routes.getValue("Advanced logs"), sosDetails)
        val physical = newPage("Physical Button Test", "Diagnostic tools")
        physicalButtonPanel.visibility = View.VISIBLE
        // Preserve the real test controller; compact its instructions and controls.
        val physicalChildren =
            (0 until physicalButtonPanel.childCount).map { physicalButtonPanel.getChildAt(it) }
        physicalChildren
            .filterIsInstance<TextView>()
            .filter { it !== physicalButtonStatus && it !== physicalButtonSummary && it !is Button }
            .forEach { physicalButtonPanel.removeView(it) }
        ui.add(physical, physicalButtonStatus, 18)
        ui.add(
            ui.card(physical),
            ui.label(
                "Press a hardware key while this screen is open. SOS key shortcut is paused during the test.",
                13f,
                muted = true,
            ),
        )
        ui.style(physicalButtonStart, primary = true)
        ui.style(physicalButtonStop)
        ui.add(physical, physicalButtonStart, 12)
        ui.add(physical, physicalButtonStop, 8)
        physicalChildren
            .filterIsInstance<Button>()
            .filter { it !== physicalButtonStart && it !== physicalButtonStop }
            .forEach {
                ui.style(it)
                ui.add(physical, it, 8)
            }
        ui.add(physical, physicalButtonSummary, 8)
        ui.add(physical, physicalButtonPanel, 8)
        ui.add(physical, ui.button("Export test results") { exportButton.performClick() }, 8)
        exportEvidence = { exportButton.performClick() }
        // Tracking has a fixed order in every operational state.
        val tracking = routes.getValue("Tracking")
        tracking.removeAllViews()
        tracking.setPadding(dp(20), dp(4), dp(20), dp(16))
        val phone = ui.card(tracking, 0)
        identityName = ui.label("Loading phone…", 18f, bold = true)
        identityMeta = ui.label("Party", 12f, muted = true)
        phone.setPadding(dp(10), dp(7), dp(10), dp(7))
        identityName.setPadding(0, 0, 0, 0)
        identityMeta.setPadding(0, 0, 0, 0)
        val identityRow =
            LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        identityBadge =
            ui.label("?", 16f, bold = true).apply {
                gravity = android.view.Gravity.CENTER
                background = ui.surface(R.color.positive_surface, 18)
                setTextColor(getColor(R.color.accent))
            }
        identityRow.addView(
            identityBadge,
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) },
        )
        identityRow.addView(
            ui.column().apply {
                addView(identityName)
                addView(identityMeta)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        ui.add(phone, identityRow)
        val pills = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        detach(trackingSummary)
        detach(commandSummary)
        ui.pill(trackingSummary)
        ui.pill(commandSummary)
        pills.addView(trackingSummary, LinearLayout.LayoutParams(-2, -2))
        pills.addView(
            commandSummary,
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) },
        )
        ui.add(tracking, pills, 12)
        val fix = ui.card(tracking, 14)
        ui.add(fix, ui.label("GNSS", 11f, muted = true, bold = true))
        val fixRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        detach(gnssSummary)
        gnssSummary.background = null
        gnssSummary.setPadding(0, dp(4), 0, dp(4))
        gnssSummary.textSize = 19f
        gnssSummary.setTypeface(null, android.graphics.Typeface.BOLD)
        fixRow.addView(gnssSummary, LinearLayout.LayoutParams(0, -2, 1f))
        fixAccuracy = ui.label("— m", 18f, bold = true)
        fixRow.addView(fixAccuracy)
        ui.add(fix, fixRow)
        fixAge = ui.label("Waiting for GNSS", 12f, muted = true)
        ui.add(fix, fixAge)
        val observations = ui.card(tracking, 14)
        ui.add(observations, ui.label("SAVED OBSERVATIONS", 11f, muted = true, bold = true))
        val metrics = LinearLayout(this)
        fun metric(caption: String): TextView {
            val column = ui.column()
            val value = ui.label("—", 25f, bold = true)
            column.addView(value)
            column.addView(ui.label(caption, 12f, muted = true))
            metrics.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            return value
        }
        pendingMetric = metric("Pending")
        deliveredMetric = metric("Command ACKed")
        ui.add(observations, metrics, 6)
        ui.add(observations, historyProgressBar, 10)
        historyProgressBar.layoutParams.height = dp(4)
        lastAckLabel = ui.label("Last ACK · Unknown", 10f, muted = true)
        ui.add(observations, lastAckLabel, 6)
        // Secondary sync and primary start/stop never move with banners.
        val actions = LinearLayout(this)
        trackingActions = actions
        detach(syncNowButton)
        detach(startButton)
        ui.style(syncNowButton)
        syncNowButton.minHeight = dp(if (resources.configuration.fontScale > 1.25f) 72 else 48)
        ui.style(startButton, primary = true)
        actions.addView(syncNowButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(
            startButton,
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) },
        )
        ui.add(tracking, actions, 18)
        ui.add(tracking, cancelStartButton, 4)
        trackingError.textSize = 13f
        trackingError.setPadding(dp(12), dp(10), dp(12), dp(10))
        trackingError.background = ui.surface(R.color.emergency_surface)
        trackingContext =
            ui.label("", 13f).apply {
                visibility = View.GONE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = ui.surface(R.color.secondary_surface)
            }
        ui.add(tracking, trackingContext, 12)
        ui.add(tracking, trackingError, 8)
        ui.add(tracking, phoneAlertActions, 6)
        // Read-only extra telemetry belongs to Diagnostics, never the routine dashboard.
        ui.add(routes.getValue("Advanced logs"), readiness)
        ui.add(routes.getValue("Advanced logs"), batterySummary)
        ui.add(routes.getValue("Storage health"), historySummary)
        ui.add(routes.getValue("Storage health"), historyProgressLabel)
        ui.add(routes.getValue("Command trust"), diagnosticTrust)
        ui.add(routes.getValue("Policy details"), reportingPolicy)
        emergencyDock.background = null
        emergencyDock.setPadding(dp(20), dp(6), dp(20), dp(8))
        val dockChildren = (0 until emergencyDock.childCount).map { emergencyDock.getChildAt(it) }
        emergencyDock.removeAllViews()
        val dock = ui.card(emergencyDock, 0, R.color.emergency_surface)
        dock.setPadding(dp(14), dp(10), dp(14), dp(10))
        ui.add(
            dock,
            ui.label("SOS", 16f, bold = true).apply {
                setTextColor(getColor(R.color.danger))
                setPadding(0, 0, 0, 0)
            },
        )
        sosStatus.textSize = 12f
        sosStatus.minHeight = dp(if (resources.configuration.fontScale > 1.25f) 52 else 20)
        sosStatus.setPadding(0, 0, 0, dp(2))
        dockChildren.forEach { ui.add(dock, it) }
        (dockChildren.last().layoutParams as LinearLayout.LayoutParams).height = dp(2)
        tabs.forEach { (name, tab) ->
            tab.textSize = 13f
            tab.backgroundTintList = null
            tab.background =
                android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_selected),
                        ui.surface(R.color.positive_surface, 12),
                    )
                    addState(
                        intArrayOf(),
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                    )
                }
        }
        navigation.setPadding(dp(12), dp(8), dp(12), dp(8))
        navigation.setBackgroundColor(getColor(R.color.surface))
        // Preserve immediate access to the same two controls when enlarged type or a short
        // landscape viewport needs the facts to scroll. Ordinary portrait follows the reference.
        val pinActions =
            resources.configuration.fontScale > 1.25f ||
                resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (pinActions) {
            detach(actions)
            root.addView(
                actions,
                root.indexOfChild(emergencyDock),
                LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = dp(20)
                    rightMargin = dp(20)
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                },
            )
            if (
                resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) {
                // Share a single short footer in landscape so alerts, tracking actions and
                // deliberate SOS remain reachable without consuming the entire viewport.
                val footer =
                    LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                detach(actions)
                detach(emergencyDock)
                root.addView(
                    footer,
                    root.indexOfChild(navigation),
                    LinearLayout.LayoutParams(-1, -2),
                )
                footer.addView(
                    actions,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        leftMargin = dp(20)
                        rightMargin = dp(6)
                    },
                )
                footer.addView(emergencyDock, LinearLayout.LayoutParams(0, -2, 1f))
                emergencyDock.setPadding(dp(6), dp(6), dp(10), dp(8))
                val title = dock.getChildAt(0)
                val sosButton = dockChildren[1]
                val progress = dockChildren[2]
                listOf(title, sosStatus, sosButton, progress).forEach(::detach)
                dock.orientation = LinearLayout.HORIZONTAL
                dock.gravity = android.view.Gravity.CENTER_VERTICAL
                sosStatus.minHeight = dp(if (resources.configuration.fontScale > 1.25f) 72 else 48)
                dock.addView(
                    ui.column().apply {
                        ui.add(this, title)
                        ui.add(this, sosStatus)
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) },
                )
                dock.addView(
                    ui.column().apply {
                        ui.add(this, sosButton)
                        ui.add(this, progress)
                        progress.layoutParams.height = dp(2)
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
            detach(trackingAlerts)
            root.addView(
                trackingAlerts,
                (if (actions.parent === root) root.indexOfChild(actions)
                else root.indexOfChild(actions.parent as View)),
                LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = dp(20)
                    rightMargin = dp(20)
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                },
            )
        }
    }

    private fun refreshTrackingBanners() {
        if (!::trackingContext.isInitialized || !::trackingAlerts.isInitialized) return
        val showAlert = destination == "Tracking" && activePhoneAlerts
        trackingAlerts.visibility = if (showAlert) View.VISIBLE else View.GONE
        // One contextual priority; all retained details remain available in Diagnostics.
        trackingError.visibility =
            if (!showAlert && trackingError.text.isNotEmpty()) View.VISIBLE else View.GONE
        trackingContext.visibility =
            if (!showAlert && trackingError.text.isEmpty() && trackingContext.text.isNotEmpty())
                View.VISIBLE
            else View.GONE
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        routeParents[route]?.let { parent ->
            navigateTo?.invoke(parent)
            return
        }
        if (destination != "Tracking") {
            navigateTo?.invoke("Tracking")
            return
        }
        val saved = savedInstallation
        val dirty =
            settingsDraftLoaded &&
                (saved == null ||
                    partyId.text.toString() != saved.partyId ||
                    partyName.text.toString() != saved.partyName ||
                    endpoint.text.toString() != saved.endpoint ||
                    interval.text.toString() != saved.localInterval.toString())
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved settings")
                .setMessage(
                    "Keep editing to Save settings, or discard these local form edits and close. Applied policy and saved observations are retained."
                )
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Discard edits") { _, _ -> super.onBackPressed() }
                .show()
        } else super.onBackPressed()
    }

    private fun restoreSettings(state: Installation?, draft: Bundle? = null) {
        partyId.setText(draft?.getString("partyId") ?: state?.partyId.orEmpty())
        partyName.setText(draft?.getString("partyName") ?: state?.partyName.orEmpty())
        endpoint.setText(draft?.getString("endpoint") ?: state?.endpoint.orEmpty())
        interval.setText(draft?.getString("interval") ?: state?.localInterval?.toString().orEmpty())
        listOf(partyId, partyName, endpoint, interval).forEach { it.isEnabled = true }
        if (draft != null) {
            for ((name, field) in
                listOf(
                    "partyId" to partyId,
                    "partyName" to partyName,
                    "endpoint" to endpoint,
                    "interval" to interval,
                )) {
                field.setSelection(
                    draft.getInt("${name}Selection", field.length()).coerceIn(0, field.length())
                )
                if (draft.getString("focus") == name) field.requestFocus()
            }
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
        if (!initialized && !start) {
            settingsFeedback.text = "Settings not saved · storage unavailable"
            return
        }
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
                    if (!start) settingsFeedback.text = "Saving locally…"
                    withContext(Dispatchers.IO) {
                        app.commandControl.changeSettings {
                            app.repository.settings(id, name, url, seconds, reenroll)
                        }
                    }
                    if (start) {
                        savingStart = false
                        attemptStart()
                    } else {
                        settingsFeedback.text = "Settings saved · local only"
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

    private fun updateStartControls(op: Operational = app.operational.value) {
        if (!::startButton.isInitialized) return
        startButton.isEnabled = initialized && !pendingStart && !op.starting && !op.stopping
        cancelStartButton.visibility = if (pendingStart || op.starting) View.VISIBLE else View.GONE
        NativeUi(this).style(startButton, primary = !op.tracking, danger = op.tracking)
        startButton.minHeight = dp(if (resources.configuration.fontScale > 1.25f) 72 else 48)
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
        readiness.text =
            "Location · ${if (state.precise) "Precise" else "Permission needed"}\n" +
                "GPS · ${if (state.gps == true) "On" else "Off / unknown"}\n" +
                "Notifications · ${if (state.notifications == true) "Allowed" else "Blocked / unknown"}\n" +
                "Battery saver · ${if (state.powerSave == false) "Off" else "On / unknown"}\n" +
                "Background · ${if (state.optimizationExemption == true) "Exempt; OEM limits may apply" else "Battery limits may apply"}"
        if (::locationReadiness.isInitialized) {
            locationReadiness.text =
                "${if (state.precise) "Precise permission" else "Permission needed"} · ${if (state.gps == true) "Location on" else "Location off / unknown"}"
            notificationReadiness.text =
                if (state.notifications == true) "Allowed" else "Blocked / unknown"
            batteryReadiness.text =
                "${if (state.powerSave == false) "Battery saver off" else "Battery saver on / unknown"} · ${if (state.optimizationExemption == true) "Exempt; OEM limits may apply" else "Background limits may apply"}"
        }
        locationAction.visibility = if (state.precise) View.GONE else View.VISIBLE
        gpsAction.visibility = View.VISIBLE
        notificationAction.visibility = View.VISIBLE
        notificationAction.text =
            if (state.notifications == true) "Notification settings" else "Allow notifications"
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumedNotificationAction = false
        setIntent(intent)
        dispatchNotificationAction()
    }

    private fun dispatchNotificationAction() {
        if (!resumed || !initialized) return
        val action = intent.action
        if (action != TrackingNotification.SYNC && action != TrackingNotification.STOP) return
        // Consume before dispatch so rotation/resume never repeats the action.
        setIntent(Intent(intent).setAction(null))
        consumedNotificationAction = true
        showTracking?.invoke()
        if (action == TrackingNotification.SYNC) syncNowButton.performClick()
        else if (app.operational.value.tracking || app.operational.value.starting) stopTracking()
        else Toast.makeText(this, "Tracking is already stopped", Toast.LENGTH_SHORT).show()
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
        dispatchNotificationAction()
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
        outState.putBoolean("consumedNotificationAction", consumedNotificationAction)
        outState.putString("destination", destination)
        outState.putString("route", route)
        outState.putString("selectedSos", selectedSos)
        outState.putInt("sosPage", sosPage)
        outState.putString("selectedInstruction", selectedInstruction)
        outState.putBoolean("instructionFullExpanded", instructionFullExpanded)
        outState.putBundle(
            "settingsDraft",
            Bundle().apply {
                for ((name, field) in
                    listOf(
                        "partyId" to partyId,
                        "partyName" to partyName,
                        "endpoint" to endpoint,
                        "interval" to interval,
                    )) {
                    putInt("${name}Selection", field.selectionStart)
                    if (field.hasFocus()) putString("focus", name)
                }
                putString("partyId", partyId.text.toString())
                putString("partyName", partyName.text.toString())
                putString("endpoint", endpoint.text.toString())
                putString("interval", interval.text.toString())
            },
        )
        consoleFilters.forEach { (label, choice) ->
            outState.putInt(label, choice.selectedItemPosition)
        }
        outState.putString("instructionFilter", instructionFilter.text.toString())
        outState.putInt("instructionPage", instructionPage)
        outState.putBoolean("pendingStart", pendingStart && !savingStart)
        outState.putBoolean("canceledSave", pendingStart && savingStart)
        outState.putBoolean("askedLocation", askedLocation)
        outState.putBoolean("askedNotifications", askedNotifications)
        outState.putInt("permissionInFlight", permissionInFlight ?: 0)
        historyProgressStartDelivered?.let { outState.putLong("historyProgressStartDelivered", it) }
        if (!settingsDraftLoaded) outState.putBundle("settingsDraft", draftState)
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
                active -> "Listening"
                history?.activityWasInterrupted == true -> "Interrupted · SOS shortcut restored"
                else -> "Test inactive"
            }
        physicalButtonStart.visibility =
            if (app.physicalButtonTest.listening) View.GONE else View.VISIBLE
        physicalButtonStop.visibility =
            if (app.physicalButtonTest.listening) View.VISIBLE else View.GONE
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
        physicalButtonSummary.text =
            "${history.totalPresses} key presses · Last ${last?.evidence?.keyName ?: "None"}\n${localTime(last?.at)} · ${history.activityResult}"
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
