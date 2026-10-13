package org.gnss.tracking

// Assess only retained, explicit evidence. Operational Room rows remain the source of truth.
fun sosReport(entries: List<DiagnosticEntry>, ioFailures: Long, dropped: Long): Map<String, Any> {
    val groups = entries.filter { it.sos?.eventRef != null }.groupBy { it.sos!!.eventRef!! }
    val assessments =
        groups.flatMap { (ref, events) ->
            fun verdict(
                name: String,
                positive: DiagnosticEvent?,
                negative: DiagnosticEvent? = null,
                reason: String,
            ): Map<String, Any> {
                val evidence = events.filter { it.event == positive || it.event == negative }
                val result =
                    when {
                        negative != null && evidence.any { it.event == negative } -> "FAIL"
                        positive != null && evidence.any { it.event == positive } -> "PASS"
                        else -> "INCONCLUSIVE"
                    }
                return mapOf(
                    "scenario" to name,
                    "event_ref" to ref,
                    "result" to result,
                    "reason" to
                        if (result == "INCONCLUSIVE") reason
                        else "Explicit retained transition evidence; see events",
                    "evidence" to evidence,
                )
            }
            listOf(
                verdict(
                    "SOS saved locally",
                    DiagnosticEvent.SOS_SAVED_LOCALLY,
                    DiagnosticEvent.SOS_SAVE_FAILED,
                    "No save result retained; dropped/pruned/crash-unflushed data cannot prove success",
                ),
                verdict(
                    "SOS preempted backlog",
                    DiagnosticEvent.SOS_PREEMPTED_BACKLOG,
                    reason = "No send selection with competing reports retained",
                ),
                // Require an actual offline observation before accepted ACK, not elapsed time
                // alone.
                verdict(
                    "SOS delivered after reconnect",
                    if (
                        events.any { it.event == DiagnosticEvent.SOS_NETWORK_UNAVAILABLE } &&
                            events.indexOfLast {
                                it.event == DiagnosticEvent.SOS_TRANSPORT_ACK_ACCEPTED
                            } >
                                events.indexOfFirst {
                                    it.event == DiagnosticEvent.SOS_NETWORK_UNAVAILABLE
                                }
                    )
                        DiagnosticEvent.SOS_TRANSPORT_ACK_ACCEPTED
                    else null,
                    reason =
                        "Requires offline evidence followed by matching ACK; this does not prove physical radio behavior",
                ),
                verdict(
                    "SOS retries deduplicated",
                    null,
                    reason =
                        "Use Command report; Android cannot prove receiver dedupe from a missing ACK",
                ),
                verdict(
                    "Operator ACK persisted",
                    null,
                    reason = "Command-only state; no phone return channel",
                ),
                verdict(
                    "SOS survived restart",
                    if (
                        events.withIndex().any { (index, saved) ->
                            saved.event == DiagnosticEvent.SOS_SAVED_LOCALLY &&
                                saved.serviceGeneration != null &&
                                events.drop(index + 1).any { restored ->
                                    restored.event == DiagnosticEvent.SOS_RESTORED &&
                                        restored.serviceGeneration != null &&
                                        restored.serviceGeneration != saved.serviceGeneration
                                }
                        }
                    )
                        DiagnosticEvent.SOS_RESTORED
                    else null,
                    reason =
                        "Requires local save and later database restoration evidence for this event",
                ),
            )
        }
    return mapOf(
        "schema_version" to 1,
        "events" to entries,
        "assessments" to assessments,
        "writer_io_failures" to ioFailures,
        "queue_dropped_total" to dropped,
        "coverage" to
            "Bounded retained timeline; missing evidence is inconclusive. Physical keys need hardware acceptance.",
    )
}
