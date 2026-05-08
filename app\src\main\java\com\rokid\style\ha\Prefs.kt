package com.rokid.style.ha

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper around SharedPreferences — mirrors iOS UserDefaults usage.
 *
 * Default HA URL uses the mDNS hostname that works on most local networks.
 * Override in Settings when using a cloud/Nabu Casa URL or a static IP.
 */
object Prefs {

    private const val PREF_FILE        = "ha_prefs"
    private const val KEY_HA_URL       = "ha_url"
    private const val KEY_HA_TOKEN     = "ha_token"
    private const val KEY_DASHBOARD    = "dashboard_entity_ids"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // HA URL  (http or https, no trailing slash — WS client converts scheme)
    // ------------------------------------------------------------------
    fun getHaUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_HA_URL, "http://homeassistant.local:8123") ?: "http://homeassistant.local:8123"

    fun setHaUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(KEY_HA_URL, url.trimEnd('/')).apply()

    // ------------------------------------------------------------------
    // Long-lived access token (generated in HA profile page)
    // ------------------------------------------------------------------
    fun getHaToken(ctx: Context): String =
        prefs(ctx).getString(KEY_HA_TOKEN, "") ?: ""

    fun setHaToken(ctx: Context, token: String) =
        prefs(ctx).edit().putString(KEY_HA_TOKEN, token.trim()).apply()

    // ------------------------------------------------------------------
    // Dashboard entity IDs — stored as comma-separated string
    // ------------------------------------------------------------------
    fun getDashboardEntityIds(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_DASHBOARD, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun setDashboardEntityIds(ctx: Context, ids: List<String>) {
        val raw = ids.joinToString(",") { it.trim() }
        prefs(ctx).edit().putString(KEY_DASHBOARD, raw).apply()
    }

    /** Convenience: raw comma-separated string for the settings UI text field. */
    fun getDashboardRaw(ctx: Context): String =
        prefs(ctx).getString(KEY_DASHBOARD, "") ?: ""

    fun setDashboardRaw(ctx: Context, raw: String) =
        prefs(ctx).edit().putString(KEY_DASHBOARD, raw).apply()

    // ------------------------------------------------------------------
    // Auto-reconnect toggle
    // ------------------------------------------------------------------
    fun isAutoReconnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_RECONNECT, true)

    fun setAutoReconnect(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------
    fun isConfigured(ctx: Context): Boolean =
        getHaToken(ctx).isNotBlank() && getHaUrl(ctx).isNotBlank()
}
