package com.rokid.style.ha

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "HaWebSocketClient"

/**
 * OkHttp-backed WebSocket client that implements the Home Assistant WebSocket API.
 *
 * Protocol flow (mirrors HAWebSocketClient.swift):
 *   1. Connect  → receive { type: "auth_required" }
 *   2. Send     { type: "auth", access_token: TOKEN }
 *   3. Receive  { type: "auth_ok" }  or  { type: "auth_invalid" }
 *   4. Send     subscribe_events (state_changed)
 *   5. Send     get_states
 *   6. Keep-alive ping every 30 s
 *
 * All callbacks are delivered on a coroutine dispatcher; callers must
 * marshal to the main thread themselves if needed.
 */
class HaWebSocketClient(
    private val scope: CoroutineScope,
    private val onStateChange: (HAConnectionState) -> Unit,
    private val onEntitiesLoaded: (List<HAEntity>) -> Unit,
    private val onEntityUpdated: (HAEntity) -> Unit
) {
    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------
    private val msgId = AtomicInteger(1)
    private var webSocket: WebSocket? = null
    private val connectionState = AtomicReference(HAConnectionState.DISCONNECTED)
    private var pingJob: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // no read timeout for WebSocket
            .pingInterval(30, TimeUnit.SECONDS) // OkHttp-level ping as backup
            .build()
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Connect to the HA WebSocket endpoint derived from [httpUrl]. */
    fun connect(httpUrl: String, token: String) {
        if (connectionState.get() == HAConnectionState.CONNECTED ||
            connectionState.get() == HAConnectionState.CONNECTING) return

        setState(HAConnectionState.CONNECTING)

        val wsUrl = httpUrl
            .replace(Regex("^http://"),  "ws://")
            .replace(Regex("^https://"), "wss://")
            .trimEnd('/') + "/api/websocket"

        Log.i(TAG, "Connecting to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, createListener(token))
    }

    /** Gracefully close the connection. */
    fun disconnect() {
        pingJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        setState(HAConnectionState.DISCONNECTED)
    }

    /**
     * Send a HA service call.
     * Returns the message ID so callers can correlate responses if needed.
     */
    fun callService(
        domain: String,
        service: String,
        entityId: String?,
        additionalData: Map<String, Any> = emptyMap()
    ): Int {
        val id = nextId()
        val payload = JSONObject().apply {
            put("id", id)
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            val serviceData = JSONObject()
            if (entityId != null) serviceData.put("entity_id", entityId)
            additionalData.forEach { (k, v) -> serviceData.put(k, v) }
            put("service_data", serviceData)
        }
        send(payload)
        Log.d(TAG, "callService id=$id $domain.$service entity=$entityId")
        return id
    }

    /** Request a fresh get_states from HA. */
    fun refreshStates() {
        val id = nextId()
        send(JSONObject().apply {
            put("id", id)
            put("type", "get_states")
        })
    }

    val isConnected: Boolean
        get() = connectionState.get() == HAConnectionState.CONNECTED

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun createListener(token: String): WebSocketListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened")
            setState(HAConnectionState.AUTHENTICATING)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.v(TAG, "Received: $text")
            scope.launch(Dispatchers.IO) {
                try {
                    handleMessage(JSONObject(text), token)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing $code $reason")
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed $code $reason")
            pingJob?.cancel()
            setState(HAConnectionState.DISCONNECTED)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            pingJob?.cancel()
            setState(HAConnectionState.DISCONNECTED)
        }
    }

    private fun handleMessage(json: JSONObject, token: String) {
        when (val type = json.optString("type")) {
            "auth_required" -> {
                Log.i(TAG, "Auth required, sending token")
                send(JSONObject().apply {
                    put("type", "auth")
                    put("access_token", token)
                })
            }

            "auth_ok" -> {
                Log.i(TAG, "Auth OK")
                setState(HAConnectionState.CONNECTED)
                subscribeStateChanges()
                requestStates()
                startApplicationPing()
            }

            "auth_invalid" -> {
                Log.e(TAG, "Auth invalid: ${json.optString("message")}")
                setState(HAConnectionState.DISCONNECTED)
            }

            "result" -> {
                if (json.optBoolean("success")) {
                    val result = json.optJSONArray("result") ?: return
                    val entities = mutableListOf<HAEntity>()
                    for (i in 0 until result.length()) {
                        HAEntity.fromJson(result.getJSONObject(i))?.let { entities.add(it) }
                    }
                    if (entities.isNotEmpty()) {
                        Log.i(TAG, "Loaded ${entities.size} entities from get_states")
                        onEntitiesLoaded(entities)
                    }
                } else {
                    Log.w(TAG, "Result error: ${json.optJSONObject("error")}")
                }
            }

            "event" -> {
                val event   = json.optJSONObject("event") ?: return
                val evtType = event.optString("event_type")
                if (evtType == "state_changed") {
                    val data     = event.optJSONObject("data") ?: return
                    val newState = data.optJSONObject("new_state") ?: return
                    HAEntity.fromJson(newState)?.let { entity ->
                        Log.d(TAG, "State changed: ${entity.entityId} → ${entity.state}")
                        onEntityUpdated(entity)
                    }
                }
            }

            "pong" -> Log.v(TAG, "Pong received")

            else -> Log.v(TAG, "Unhandled message type: $type")
        }
    }

    /** Subscribe to state_changed events. */
    private fun subscribeStateChanges() {
        send(JSONObject().apply {
            put("id", nextId())
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        })
    }

    /** Request all entity states. */
    private fun requestStates() {
        send(JSONObject().apply {
            put("id", nextId())
            put("type", "get_states")
        })
    }

    /** Application-level ping every 30 s (in addition to OkHttp TCP ping). */
    private fun startApplicationPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                if (connectionState.get() == HAConnectionState.CONNECTED) {
                    send(JSONObject().apply {
                        put("id", nextId())
                        put("type", "ping")
                    })
                }
            }
        }
    }

    private fun send(json: JSONObject) {
        val text = json.toString()
        val sent = webSocket?.send(text) ?: false
        if (!sent) Log.w(TAG, "Failed to send: $text")
    }

    private fun nextId(): Int = msgId.getAndIncrement()

    private fun setState(state: HAConnectionState) {
        connectionState.set(state)
        onStateChange(state)
    }
}
