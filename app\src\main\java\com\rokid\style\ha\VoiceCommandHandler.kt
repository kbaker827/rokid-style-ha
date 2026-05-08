package com.rokid.style.ha

import android.util.Log

private const val TAG = "VoiceCommandHandler"

/**
 * Parses a raw voice transcript into an [HACommand].
 *
 * Supported phrases (case-insensitive):
 *   "lights on / off"                     → turn all lights on/off
 *   "[entity name] on / off"              → match entity by friendly name or domain
 *   "turn on/off [entity name]"           → same, reversed word order
 *   "lock / unlock [entity name]"         → lock domain
 *   "status"                              → speak all monitored entities
 *
 * Matching strategy:
 *   1. Exact friendly-name match (lowercased, stripped punctuation)
 *   2. Partial match (transcript contains entity name or vice versa)
 *   3. Domain-level match ("lights on" → every entity in domain "light")
 *
 * This is intentionally simple and extendable — no NLP library required.
 */
class VoiceCommandHandler(
    private val entities: () -> List<HAEntity>
) {

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun parse(transcript: String): HACommand {
        val text = transcript.trim().lowercase()
        Log.d(TAG, "Parsing: \"$text\"")

        // --- status ---
        if (text.contains("status") || text.contains("what's on") || text.contains("report")) {
            return HACommand.GetStatus
        }

        // --- lights on / lights off (domain shortcut) ---
        if (text.matches(Regex(".*\\blights?\\s+(on|off)\\b.*"))) {
            val service = if (text.contains("\\bon\\b".toRegex())) "turn_on" else "turn_off"
            return HACommand.CallService("light", service, entityId = null)
        }
        if (text.matches(Regex(".*\\b(turn|switch)\\s+(on|off)\\s+(all\\s+)?lights?\\b.*"))) {
            val service = if (text.contains("\\bon\\b".toRegex())) "turn_on" else "turn_off"
            return HACommand.CallService("light", service, entityId = null)
        }

        // --- lock / unlock ---
        val lockMatch = Regex("\\b(lock|unlock)\\b(.*)").find(text)
        if (lockMatch != null) {
            val action    = lockMatch.groupValues[1]   // "lock" or "unlock"
            val remainder = lockMatch.groupValues[2].trim()
            val service   = if (action == "lock") "lock" else "unlock"
            val entity    = findEntity(remainder, domain = "lock")
            if (entity != null) {
                return HACommand.CallService("lock", service, entity.entityId)
            }
        }

        // --- turn on/off [name] ---
        val turnMatch = Regex("\\b(turn|switch|set)\\s+(on|off)\\s+(.+)").find(text)
        if (turnMatch != null) {
            val onOff  = turnMatch.groupValues[2]
            val name   = turnMatch.groupValues[3].trim()
            val service = if (onOff == "on") "turn_on" else "turn_off"
            val entity  = findEntity(name)
            if (entity != null) {
                return HACommand.CallService(entity.domain, service, entity.entityId)
            }
        }

        // --- [name] on/off ---
        val nameFirstMatch = Regex("(.+?)\\s+(on|off)$").find(text)
        if (nameFirstMatch != null) {
            val name    = nameFirstMatch.groupValues[1].trim()
            val onOff   = nameFirstMatch.groupValues[2]
            val service = if (onOff == "on") "turn_on" else "turn_off"
            val entity  = findEntity(name)
            if (entity != null) {
                return HACommand.CallService(entity.domain, service, entity.entityId)
            }
        }

        Log.d(TAG, "No command matched for: \"$text\"")
        return HACommand.Unknown
    }

    // -----------------------------------------------------------------------
    // Entity matching
    // -----------------------------------------------------------------------

    /**
     * Find the best matching entity for [query].
     * Optionally restrict to a specific [domain].
     */
    private fun findEntity(query: String, domain: String? = null): HAEntity? {
        val candidates = entities().let { all ->
            if (domain != null) all.filter { it.domain == domain } else all
        }

        if (query.isBlank()) return null

        val normalised = query.normalise()

        // 1. Exact friendly-name match
        candidates.firstOrNull { it.displayName.normalise() == normalised }?.let { return it }

        // 2. Exact entity-id tail match (e.g. "kitchen" matches "light.kitchen")
        candidates.firstOrNull {
            it.entityId.substringAfter(".").replace("_", " ").normalise() == normalised
        }?.let { return it }

        // 3. Partial: query contains entity name
        candidates.firstOrNull { entity ->
            normalised.contains(entity.displayName.normalise())
        }?.let { return it }

        // 4. Partial: entity name contains query
        candidates.firstOrNull { entity ->
            entity.displayName.normalise().contains(normalised)
        }?.let { return it }

        return null
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun String.normalise(): String =
        this.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
