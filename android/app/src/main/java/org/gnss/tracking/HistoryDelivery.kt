package org.gnss.tracking

import com.google.gson.JsonArray
import com.google.gson.JsonObject

// Roles never mutate an observation's durable envelope. Batch identity is the
// ordered set of original message identities, checked completely before Room ACK.
object HistoryProtocol {
    fun batch(rows: List<Outbound>): String =
        JsonObject()
            .apply {
                addProperty("batch_version", 1)
                add("messages", JsonArray().apply { rows.forEach { add(Protocol.parse(it.json)) } })
            }
            .toString()

    fun receipts(json: String, rows: List<Outbound>): List<Receipt> {
        val body = Protocol.parse(json)
        require(Protocol.whole(body, "batch_version", 1, 1) == 1L)
        val a = Protocol.field(body, "acks")
        require(a.isJsonArray && a.asJsonArray.size() == rows.size)
        return rows.indices.map {
            Protocol.receipt(a.asJsonArray[it].toString(), Protocol.decodeMessage(rows[it].json))
        }
    }
}

class BatchAdaptation {
    private val ladder = intArrayOf(0, 500, 1000, 2000, 4000, 8000, 16000, 20000)
    var level = 0
        private set

    private var successes = 0
    val target
        get() = ladder[level]

    fun reset() {
        level = 0
        successes = 0
    }

    fun acknowledged() {
        successes++
        if (successes >= if (target <= 2000) 1 else 2) {
            level = (level + 1).coerceAtMost(ladder.lastIndex)
            successes = 0
        }
    }

    fun reachableFailure() {
        level = (level - 1).coerceAtLeast(0)
        successes = 0
    }
}
