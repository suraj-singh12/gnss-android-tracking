package org.gnss.tracking

// Roles never mutate an observation's durable envelope. Batch identity is the
// ordered set of original message identities, checked completely before Room ACK.
object HistoryProtocol {
    // Size the immutable envelopes once; never repeatedly serialize a shrinking batch.
    fun bounded(
        rows: List<Outbound>,
        maximumBytes: Int,
        maximumRecords: Int,
    ): Pair<List<Outbound>, String?> {
        var bytes = 32 // exact empty wrapper is smaller; reserve comma/wrapper overhead
        val selected = ArrayList<Outbound>()
        for (row in rows.take(maximumRecords)) {
            val size = row.json.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > maximumBytes) break
            selected.add(row)
            bytes += size
        }
        return if (selected.isEmpty()) rows.take(1) to null else selected to batch(selected)
    }

    fun batch(rows: List<Outbound>): String =
        rows.joinToString(
            separator = ",",
            prefix = "{\"batch_version\":1,\"messages\":[",
            postfix = "]}",
        ) {
            it.json
        }

    fun receipts(json: String, rows: List<Outbound>): List<Receipt> {
        val body = Protocol.parse(json, 2097152)
        require(Protocol.whole(body, "batch_version", 1, 1) == 1L)
        val a = Protocol.field(body, "acks")
        require(a.isJsonArray && a.asJsonArray.size() == rows.size)
        return rows.indices.map {
            Protocol.receipt(a.asJsonArray[it].toString(), Protocol.decodeMessage(rows[it].json))
        }
    }
}

class BatchAdaptation {
    private val ladder =
        intArrayOf(
            0,
            500,
            1000,
            2000,
            4000,
            8000,
            16000,
            20000,
            32000,
            64000,
            131072,
            262144,
            524288,
            1048576,
            2097152,
        )
    var maximum = 64000
    var level = 0
        private set

    private var successes = 0
    private var bestGoodput = 0.0
    private var previousRtt = 0L
    val target
        get() = minOf(ladder[level], maximum)

    fun reset() {
        level = 0
        successes = 0
        bestGoodput = 0.0
        previousRtt = 0
    }

    fun acknowledged(bytes: Long = 0, rttMillis: Long = 0, aggressive: Boolean = false) {
        // Compare useful payload goodput, not RSSI (which does not measure receiver load).
        // A large RTT increase with falling goodput is pressure; avoid growing into it.
        val goodput = if (rttMillis > 0) bytes.toDouble() / rttMillis else 0.0
        if (
            previousRtt > 0 &&
                rttMillis > maxOf(1500, previousRtt * 2) &&
                bestGoodput > 0 &&
                goodput < bestGoodput * 0.6
        ) {
            reachableFailure()
            previousRtt = rttMillis
            return
        }
        if (goodput > 0) bestGoodput = maxOf(goodput, bestGoodput * 0.95)
        if (rttMillis > 0)
            previousRtt = if (previousRtt == 0L) rttMillis else (previousRtt * 3 + rttMillis) / 4
        successes++
        val healthy = rttMillis == 0L || rttMillis <= 1500
        if (successes >= if (target <= 2000 || aggressive && healthy) 1 else 2) {
            // Manual drain accelerates discovery; automatic remains continuously work-conserving.
            level =
                (level + if (aggressive && healthy) 2 else 1).coerceAtMost(
                    ladder.indexOfLast { it <= maximum }.coerceAtLeast(0)
                )
            successes = 0
        }
    }

    fun resumeAfterLongGap() {
        level = minOf(level, ladder.indexOfLast { it <= 32000 })
        successes = 0
    }

    fun reachableFailure() {
        level = (level - 1).coerceAtLeast(0)
        successes = 0
    }
}
