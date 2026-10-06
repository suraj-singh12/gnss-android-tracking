package org.gnss.tracking

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

const val MAX_WIRE_INTEGER = 9007199254740991L

fun validInterval(seconds: Int) = seconds in 10..86400 && seconds % 10 == 0

fun validLabel(value: String) = value.isNotBlank() && value.codePointCount(0, value.length) <= 80

fun utc(millis: Long): String =
    DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(millis))

data class Party(val id: String, val name: String)

data class RemoteConfig(
    val authority_id: String,
    val version: Long,
    val reporting_interval_override_s: Int?,
)

data class ConfigState(
    val authority_id: String? = null,
    val version: Long = 0,
    val reporting_interval_override_s: Int? = null,
    val local_reporting_interval_s: Int = 10,
    val effective_reporting_interval_s: Int =
        reporting_interval_override_s ?: local_reporting_interval_s,
)

data class Health(
    val battery_percent: Int? = null,
    val charging: Boolean? = null,
    val wifi_connected: Boolean? = null,
    val wifi_rssi_dbm: Int? = null,
    val gnss_status: String = "unknown",
    val satellites_used: Int? = null,
)

data class Fix(
    val observed_at: String,
    val fix_age_ms: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontal_accuracy_m: Double?,
    val altitude_m: Double?,
    val altitude_accuracy_m: Double?,
    val speed_mps: Double?,
    val bearing_deg: Double?,
)

data class Sos(val event_id: String, val triggered_at: String)

data class Message(
    val protocol_version: Int = 1,
    val type: String,
    val device_id: String,
    val party: Party,
    val message_id: String,
    val sequence: Long,
    val captured_at: String,
    val config_state: ConfigState,
    val health: Health,
    val fix: Fix?,
    val sos: Sos? = null,
)

data class Receipt(
    val device: String,
    val message: String,
    val sequence: Long,
    val receivedAt: String,
    val config: RemoteConfig?,
    val configError: String?,
)

object Protocol {
    private val gson = GsonBuilder().serializeNulls().create()
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val integer = Regex("0|[1-9][0-9]*")

    fun newId(): String = UUID.randomUUID().toString()

    fun isUuid(s: String) = uuid.matches(s)

    // Streaming parsing rejects duplicate keys at every depth, including ignored fields.
    fun parse(json: String): JsonObject {
        require(json.toByteArray(Charsets.UTF_8).size <= 65536) { "JSON exceeds limit" }
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            fun value(depth: Int): JsonElement {
                require(depth <= 32) { "JSON nesting limit" }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT ->
                        JsonObject().apply {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                require(!has(key)) { "Duplicate JSON key" }
                                add(key, value(depth + 1))
                            }
                            reader.endObject()
                        }
                    JsonToken.BEGIN_ARRAY ->
                        JsonArray().apply {
                            reader.beginArray()
                            while (reader.hasNext()) add(value(depth + 1))
                            reader.endArray()
                        }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> {
                        reader.nextNull()
                        JsonNull.INSTANCE
                    }
                    else -> error("Invalid JSON")
                }
            }
            val result = value(0)
            require(reader.peek() == JsonToken.END_DOCUMENT && result.isJsonObject)
            return result.asJsonObject
        }
    }

    fun field(o: JsonObject, k: String): JsonElement = o.get(k) ?: error("Missing $k")

    fun obj(o: JsonObject, k: String): JsonObject =
        field(o, k).also { require(it.isJsonObject) }.asJsonObject

    fun str(o: JsonObject, k: String): String =
        field(o, k).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString

    fun whole(o: JsonObject, k: String, min: Long = 0, max: Long = MAX_WIRE_INTEGER): Long {
        val e = field(o, k)
        require(e.isJsonPrimitive && e.asJsonPrimitive.isNumber && integer.matches(e.toString())) {
            "Invalid $k"
        }
        return e.toString().toLongOrNull()?.also { require(it in min..max) { "Invalid $k" } }
            ?: error("Invalid $k")
    }

    fun stamp(o: JsonObject, k: String): String =
        str(o, k).also {
            require(
                Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
                    .matches(it)
            )
            require(utc(Instant.parse(it).toEpochMilli()) == it)
        }

    private fun id(o: JsonObject, k: String) = str(o, k).also { require(isUuid(it)) }

    private fun nullableNumber(o: JsonObject, k: String, min: Double, max: Double): Double? {
        val e = field(o, k)
        if (e.isJsonNull) return null
        require(e.isJsonPrimitive && e.asJsonPrimitive.isNumber)
        return e.asDouble.also { require(it.isFinite() && it in min..max) }
    }

    private fun interval(o: JsonObject, k: String): Int =
        whole(o, k, 10, 86400).toInt().also { require(validInterval(it)) }

    fun remote(o: JsonObject): RemoteConfig {
        val authority = id(o, "authority_id")
        val version = whole(o, "version")
        val override =
            if (field(o, "reporting_interval_override_s").isJsonNull) null
            else interval(o, "reporting_interval_override_s")
        require(version != 0L || override == null)
        return RemoteConfig(authority, version, override)
    }

    fun encode(m: Message): String {
        val tree = gson.toJsonTree(m).asJsonObject
        if (m.type != "sos") tree.remove("sos")
        val json = gson.toJson(tree)
        decodeMessage(json) // Validate before committing any snapshot.
        return json
    }

    fun decodeMessage(json: String): Message {
        val o = parse(json)
        require(whole(o, "protocol_version", 1, 1) == 1L)
        id(o, "device_id")
        id(o, "message_id")
        whole(o, "sequence", 1)
        stamp(o, "captured_at")
        val p = obj(o, "party")
        require(validLabel(str(p, "id")) && validLabel(str(p, "name")))
        val c = obj(o, "config_state")
        if (field(c, "authority_id").isJsonNull) {
            require(
                whole(c, "version") == 0L && field(c, "reporting_interval_override_s").isJsonNull
            )
        } else remote(c)
        val local = interval(c, "local_reporting_interval_s")
        val override =
            if (field(c, "reporting_interval_override_s").isJsonNull) null
            else interval(c, "reporting_interval_override_s")
        require(interval(c, "effective_reporting_interval_s") == (override ?: local))
        val h = obj(o, "health")
        for (k in listOf("charging", "wifi_connected")) {
            val e = field(h, k)
            require(e.isJsonNull || (e.isJsonPrimitive && e.asJsonPrimitive.isBoolean))
        }
        if (!field(h, "battery_percent").isJsonNull) whole(h, "battery_percent", 0, 100)
        if (!field(h, "satellites_used").isJsonNull) whole(h, "satellites_used")
        val rssi = nullableNumber(h, "wifi_rssi_dbm", -127.0, 0.0)
        if (rssi != null)
            require(field(h, "wifi_rssi_dbm").toString().matches(Regex("-?(0|[1-9][0-9]*)")))
        val status = str(h, "gnss_status")
        require(status in listOf("fix", "no_fix", "disabled", "unknown"))
        val f = field(o, "fix")
        if (!f.isJsonNull) {
            require(f.isJsonObject)
            val fix = f.asJsonObject
            stamp(fix, "observed_at")
            whole(fix, "fix_age_ms")
            require(nullableNumber(fix, "latitude", -90.0, 90.0) != null)
            require(nullableNumber(fix, "longitude", -180.0, 180.0) != null)
            for (k in
                listOf("horizontal_accuracy_m", "altitude_accuracy_m", "speed_mps")) nullableNumber(
                fix,
                k,
                0.0,
                Double.MAX_VALUE,
            )
            val altitude = nullableNumber(fix, "altitude_m", -Double.MAX_VALUE, Double.MAX_VALUE)
            require(altitude != null || field(fix, "altitude_accuracy_m").isJsonNull)
            nullableNumber(fix, "bearing_deg", 0.0, 360.0)?.let { require(it < 360) }
        }
        when (str(o, "type")) {
            "location" -> require(!f.isJsonNull && status == "fix" && !o.has("sos"))
            "status" -> require(f.isJsonNull && !o.has("sos"))
            "sos" -> {
                val s = obj(o, "sos")
                require(id(s, "event_id") == str(o, "message_id"))
                stamp(s, "triggered_at")
            }
            else -> error("Unknown message type")
        }
        return gson.fromJson(o, Message::class.java)
    }

    fun receipt(json: String, expected: Message): Receipt {
        val o = parse(json)
        whole(o, "protocol_version", 1, 1)
        val device = id(o, "device_id")
        val message = id(o, "message_id")
        val sequence = whole(o, "sequence", 1)
        require(
            device == expected.device_id &&
                message == expected.message_id &&
                sequence == expected.sequence
        ) {
            "ACK identity mismatch"
        }
        require(str(o, "result") in listOf("stored", "duplicate"))
        val received = stamp(o, "received_at")
        // A valid receipt and an invalid config are separate protocol outcomes.
        require(o.has("config")) { "Missing ACK config" }
        val config = runCatching { remote(obj(o, "config")) }
        return Receipt(
            device,
            message,
            sequence,
            received,
            config.getOrNull(),
            config.exceptionOrNull()?.message
                ?: if (config.isFailure) "Invalid Command configuration" else null,
        )
    }
}

sealed interface ConfigDecision {
    data class Applied(val state: ConfigState) : ConfigDecision

    data object NoChange : ConfigDecision

    data class Error(val reason: String) : ConfigDecision
}

fun applyConfig(old: ConfigState, incoming: RemoteConfig): ConfigDecision {
    if (old.authority_id != null && old.authority_id != incoming.authority_id)
        return ConfigDecision.Error("Command authority changed; explicit re-enrollment required")
    if (old.authority_id == null || incoming.version > old.version)
        return ConfigDecision.Applied(
            ConfigState(
                incoming.authority_id,
                incoming.version,
                incoming.reporting_interval_override_s,
                old.local_reporting_interval_s,
            )
        )
    if (
        incoming.version == old.version &&
            incoming.reporting_interval_override_s != old.reporting_interval_override_s
    )
        return ConfigDecision.Error("Command configuration conflicts with applied version")
    return ConfigDecision.NoChange
}
