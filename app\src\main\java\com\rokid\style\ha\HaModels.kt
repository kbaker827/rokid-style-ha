package com.rokid.style.ha

import org.json.JSONObject

// ---------------------------------------------------------------------------
// HAEntity — mirrors the iOS struct of the same purpose
// ---------------------------------------------------------------------------
data class HAEntity(
    val entityId: String,
    val state: String,
    val friendlyName: String?,
    val unit: String?,
    val deviceClass: String?,
    val domain: String
) {
    /** Human-readable label used in TTS output. */
    val displayName: String
        get() = friendlyName?.takeIf { it.isNotBlank() } ?: entityId

    /**
     * Human-readable state string that maps raw HA states to natural language,
     * with optional unit appended for sensor/numeric values.
     */
    val displayState: String
        get() = when {
            unit != null && state.toDoubleOrNull() != null -> "$state $unit"
            else -> when (state.lowercase()) {
                "on"          -> "on"
                "off"         -> "off"
                "open"        -> "open"
                "closed"      -> "closed"
                "locked"      -> "locked"
                "unlocked"    -> "unlocked"
                "unavailable" -> "unavailable"
                "unknown"     -> "unknown"
                "home"        -> "home"
                "not_home"    -> "away"
                "playing"     -> "playing"
                "paused"      -> "paused"
                "idle"        -> "idle"
                else          -> state
            }
        }

    companion object {
        /**
         * Parse an HA state object (from get_states result or state_changed event).
         *
         * Example JSON shape:
         * {
         *   "entity_id": "light.kitchen",
         *   "state": "on",
         *   "attributes": {
         *     "friendly_name": "Kitchen Light",
         *     "unit_of_measurement": null,
         *     "device_class": null
         *   }
         * }
         */
        fun fromJson(json: JSONObject): HAEntity? {
            return try {
                val entityId = json.getString("entity_id")
                val state    = json.optString("state", "unknown")
                val attrs    = json.optJSONObject("attributes") ?: JSONObject()

                val friendlyName = attrs.optString("friendly_name", "").takeIf { it.isNotBlank() }
                val unit         = attrs.optString("unit_of_measurement", "").takeIf { it.isNotBlank() }
                val deviceClass  = attrs.optString("device_class", "").takeIf { it.isNotBlank() }
                val domain       = entityId.substringBefore(".")

                HAEntity(
                    entityId    = entityId,
                    state       = state,
                    friendlyName = friendlyName,
                    unit        = unit,
                    deviceClass = deviceClass,
                    domain      = domain
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DashboardItem — a pinned entity the user wants to monitor
// ---------------------------------------------------------------------------
data class DashboardItem(val entityId: String)

// ---------------------------------------------------------------------------
// HAConnectionState — mirrors the iOS enum
// ---------------------------------------------------------------------------
enum class HAConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING
}

// ---------------------------------------------------------------------------
// HACommand — internal sealed class used by VoiceCommandHandler
// ---------------------------------------------------------------------------
sealed class HACommand {
    data class CallService(
        val domain: String,
        val service: String,
        val entityId: String?,
        val additionalData: Map<String, Any> = emptyMap()
    ) : HACommand()

    object GetStatus : HACommand()
    object Unknown   : HACommand()
}
