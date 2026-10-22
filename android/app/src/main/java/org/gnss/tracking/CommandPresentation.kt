package org.gnss.tracking

// Presentation only: preserve the canonical outcome/reason alongside its explanation.
internal fun commandOutcomeExplanation(state: String): String =
    when (state) {
        "received" -> "Received durably; execution is pending or unconfirmed."
        "applied" -> "The recorded setting or operation was applied."
        "rejected" -> "Not applied. Review the recorded reason and requested values."
        "blocked" -> "Could not execute. Review permissions, foreground readiness and the reason."
        "expired" -> "Validity ended; this outcome does not prove an earlier output was observed."
        "duplicate" -> "Already recorded; duplicate receipt does not prove a second execution."
        "output_attempted" ->
            "Notification/sound/vibration output attempted; hearing, feeling and human acknowledgement are unconfirmed."
        "alert_blocked" ->
            "Android blocked output; check notification permission and alert channel settings. No human acknowledgement is implied."
        "recipient_acknowledged" ->
            "A local recipient acknowledgement was recorded; Command result custody is separate."
        "cancel_pending" -> "Cancellation requested; stopping output is not yet confirmed."
        "cancelled" -> "Cancellation recorded; review stages for confirmed stopping of output."
        "unknown" -> "The actual effect is unconfirmed. Do not assume success or repeat execution."
        else -> "Review the canonical reason and stages; no additional outcome is inferred."
    }

internal fun commandInstructionDetails(row: CommandRecord): String = buildString {
    appendLine("${if (row.trusted) "Command UUID" else "Security event ID"}: ${row.id}")
    appendLine("Type: ${row.operation} · outcome: ${row.state}")
    appendLine("${if (row.trusted) "Trusted" else "Unverified"} Command identity: ${row.authority}")
    appendLine("Phone: ${row.device}")
    appendLine("Received: ${utc(row.received)}")
    appendLine(
        "Verification: ${if (row.trusted) "Authenticated" else "Failed or unverified; see reason"}"
    )
    appendLine(
        "Execution: ${if (row.attempted) "Attempt recorded" else "No execution attempt recorded"}"
    )
    appendLine("Reason: ${row.reason}")
    commandReasonExplanation(row.reason)?.let { appendLine(it) }
    appendLine(commandOutcomeExplanation(row.state))
    appendLine("Created: ${row.created.takeIf { it > 0 }?.let(::utc) ?: "Unknown"}")
    appendLine("Expires: ${row.expires.takeIf { it > 0 }?.let(::utc) ?: "Unknown"}")
    appendLine("Attempt: ${row.attemptedAt.takeIf { it > 0 }?.let(::utc) ?: "Unconfirmed"}")
    appendLine("Result: ${row.completedAt.takeIf { it > 0 }?.let(::utc) ?: "Pending or unknown"}")
    appendLine("Requested: ${row.requested.ifEmpty { "Unknown" }}")
    appendLine("Effective: ${row.effective.ifEmpty { "Unconfirmed" }}")
    appendLine(
        "Result delivery: ${if (row.confirmed) "Authenticated custody confirmed" else "Pending or unknown delivery"}"
    )
    appendLine("Custody: ${row.confirmedAt.takeIf { it > 0 }?.let(::utc) ?: "Unconfirmed"}")
    appendLine(
        "Result revision: ${row.revision} · protocol: ${row.version} · priority: ${row.priority}"
    )
    append(row.events)
}

internal fun commandTrustDescription(
    state: Installation?,
    rows: List<CommandRecord>,
    availability: String,
): String {
    val lastEvidence =
        rows
            .asSequence()
            .filter {
                it.trusted &&
                    state != null &&
                    it.enrollment == state.controlEnrollment &&
                    it.authority == state.controlAuthority
            }
            .maxOfOrNull { maxOf(it.received, it.confirmedAt) }
    return buildString {
        appendLine(
            if (state == null) "Enrollment unknown · loading local storage"
            else if (state.controlEnrollment == null) "Unenrolled · remote instructions unavailable"
            else "Authority bound: ${state.controlAuthority}"
        )
        appendLine("Saved endpoint: ${state?.endpoint?.ifEmpty { "Not configured" } ?: "Unknown"}")
        appendLine(availability)
        rows
            .firstOrNull { !it.trusted && it.enrollment == state?.controlEnrollment }
            ?.let {
                appendLine(
                    "Last security rejection (${utc(it.received)}): ${it.reason} · ${commandReasonExplanation(it.reason) ?: "Review Command Instructions"}"
                )
            }
        appendLine(
            "Last successful authenticated contact: ${if (availability.startsWith("Authenticated Command")) availability.substringAfter(" · ") else "Unknown; current connection is unconfirmed"}"
        )
        append(
            "Last retained authenticated instruction/result evidence: ${lastEvidence?.let(::utc) ?: "None"}. Empty authenticated polls are not retained in the journal. Wi-Fi and message ACK do not establish remote-control trust."
        )
    }
}

internal fun reportingPolicyDescription(state: Installation, rows: List<CommandRecord>): String {
    val requested =
        rows.firstOrNull {
            it.trusted && it.operation == "reporting" && it.enrollment == state.controlEnrollment
        }
    return buildString {
        appendLine("Configured local: ${state.localInterval} s")
        appendLine(
            "Command-requested: ${requested?.let { "${it.requested} · ${it.state} · ${it.reason}" } ?: "Unknown / no retained instruction for this enrollment"}"
        )
        appendLine("Active Command override: ${state.overrideSeconds?.let { "$it s" } ?: "None"}")
        appendLine(
            "Actually applied: ${state.config().effective_reporting_interval_s} s · revision ${state.version}"
        )
        append(
            state.configError
                ?: "Local Save does not prove Command requested or applied a change. Inspect Command Instructions for pending/rejected requests."
        )
    }
}

internal fun commandReasonExplanation(reason: String): String? =
    when (reason) {
        "invalid_signature" ->
            "Verification failed. Verify the enrolled Command and private provisioning; do not trust this response."
        "authority_or_freshness_mismatch" ->
            "Command identity, response correlation or clock freshness did not match. Verify Command identity and clocks."
        "invalid_authority_or_target" ->
            "Instruction authority, phone identity or enrollment did not match this installation."
        "command_storage_pressure" ->
            "Too many unresolved instructions. Restore authenticated result delivery before receiving more."
        "malformed_instruction_metadata",
        "invalid_instruction_metadata",
        "invalid_or_untrusted_control_response" ->
            "The response could not be verified as a supported instruction. Check Command compatibility and enrollment."
        else -> null
    }
