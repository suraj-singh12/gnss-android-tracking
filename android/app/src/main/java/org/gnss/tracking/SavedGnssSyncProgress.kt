package org.gnss.tracking

// Presentation only: the durable Android outbox is authoritative.
// New observations can increase the denominator while syncing.
internal data class SavedGnssSyncProgress(
    val acknowledged: Long,
    val pending: Long,
    val total: Long,
    val percentage: Int,
)

internal fun savedGnssSyncProgress(
    deliveredAtStart: Long,
    deliveredNow: Long,
    pendingNow: Long,
): SavedGnssSyncProgress {
    val acknowledged = (deliveredNow - deliveredAtStart).coerceAtLeast(0L)
    val pending = pendingNow.coerceAtLeast(0L)
    val total = acknowledged + pending
    val percentage = if (total == 0L) 100 else (acknowledged * 100L / total).toInt()
    return SavedGnssSyncProgress(acknowledged, pending, total, percentage)
}
