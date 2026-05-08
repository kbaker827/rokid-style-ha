package com.rokid.style.ha

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

private const val TAG              = "HaService"
private const val NOTIF_CHANNEL_ID = "ha_service_channel"
private const val NOTIF_ID         = 1
private const val RECONNECT_DELAY_MS = 10_000L
private const val STATUS_INTERVAL_MS = 10_000L     // how often to push status (audio)

/**
 * Foreground service that owns:
 *  - [HaWebSocketClient]  — HA WebSocket connection
 *  - [SpeechManager]      — continuous voice recognition
 *  - [TtsManager]         — text-to-speech output
 *  - [VoiceCommandHandler] — parse transcripts → HA commands
 *
 * Lifecycle mirrors HAViewModel.swift: connect on start, auto-reconnect
 * after [RECONNECT_DELAY_MS], periodic status announcements.
 */
class HaService : Service() {

    // -----------------------------------------------------------------------
    // Binder (for MainActivity to query state)
    // -----------------------------------------------------------------------
    inner class LocalBinder : Binder() {
        fun getService(): HaService = this@HaService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var tts:      TtsManager
    private lateinit var wsClient: HaWebSocketClient
    private lateinit var speech:   SpeechManager
    private lateinit var cmdHandler: VoiceCommandHandler

    // Live entity cache — keyed by entity_id
    private val entityCache = mutableMapOf<String, HAEntity>()

    // Pinned dashboard items from prefs
    private val dashboardIds: List<String>
        get() = Prefs.getDashboardEntityIds(applicationContext)

    // Current HA connection state
    @Volatile private var connectionState = HAConnectionState.DISCONNECTED

    // Jobs
    private var reconnectJob: Job? = null
    private var statusJob:    Job? = null

    // -----------------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(HAConnectionState.DISCONNECTED))

        tts = TtsManager(this) {
            // TTS ready — greet then start listening
            tts.speak("Home Assistant voice control ready.")
            startSpeech()
        }

        cmdHandler = VoiceCommandHandler { entityCache.values.toList() }

        wsClient = HaWebSocketClient(
            scope           = serviceScope,
            onStateChange   = ::handleConnectionStateChange,
            onEntitiesLoaded = ::handleEntitiesLoaded,
            onEntityUpdated  = ::handleEntityUpdated
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received")
                stopSelf()
            }
            else -> {
                Log.i(TAG, "Start command received")
                connectToHA()
            }
        }
        return START_STICKY   // restart if killed by system
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        reconnectJob?.cancel()
        statusJob?.cancel()
        speech.stop()
        wsClient.disconnect()
        tts.speak("Home Assistant disconnected.", interrupt = true)
        serviceScope.launch {
            delay(2000)
            tts.shutdown()
            serviceScope.cancel()
        }
    }

    // -----------------------------------------------------------------------
    // HA connection
    // -----------------------------------------------------------------------

    private fun connectToHA() {
        val url   = Prefs.getHaUrl(applicationContext)
        val token = Prefs.getHaToken(applicationContext)
        if (token.isBlank()) {
            Log.w(TAG, "No HA token configured")
            tts.speak("Please configure your Home Assistant token in Settings.")
            return
        }
        Log.i(TAG, "Connecting to $url")
        wsClient.connect(url, token)
    }

    private fun handleConnectionStateChange(state: HAConnectionState) {
        connectionState = state
        updateNotification(state)

        when (state) {
            HAConnectionState.CONNECTED -> {
                Log.i(TAG, "Connected to Home Assistant")
                reconnectJob?.cancel()
                tts.speak("Connected to Home Assistant.")
                startPeriodicStatus()
            }

            HAConnectionState.DISCONNECTED -> {
                Log.i(TAG, "Disconnected from Home Assistant")
                statusJob?.cancel()
                scheduleReconnect()
            }

            HAConnectionState.AUTHENTICATING -> {
                Log.i(TAG, "Authenticating…")
            }

            else -> { /* CONNECTING / RECONNECTING — no action */ }
        }
    }

    private fun scheduleReconnect() {
        if (!Prefs.isAutoReconnect(applicationContext)) return
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            Log.i(TAG, "Reconnecting in ${RECONNECT_DELAY_MS / 1000}s…")
            delay(RECONNECT_DELAY_MS)
            connectToHA()
        }
    }

    // -----------------------------------------------------------------------
    // Entity state management
    // -----------------------------------------------------------------------

    private fun handleEntitiesLoaded(entities: List<HAEntity>) {
        synchronized(entityCache) {
            entities.forEach { entityCache[it.entityId] = it }
        }
        Log.i(TAG, "Entity cache populated: ${entityCache.size} entities")
    }

    private fun handleEntityUpdated(entity: HAEntity) {
        val previous: HAEntity?
        synchronized(entityCache) {
            previous = entityCache[entity.entityId]
            entityCache[entity.entityId] = entity
        }

        // Only announce if:
        //  (a) entity is in the dashboard (pinned list), AND
        //  (b) state actually changed
        val ids = dashboardIds
        if (ids.isNotEmpty() && entity.entityId !in ids) return
        if (previous?.state == entity.state) return

        val alert = alertText(entity)
        if (alert != null) {
            Log.i(TAG, "Alert: $alert")
            tts.speak(alert)
        }
    }

    /**
     * Generate a spoken alert for a state change — mirrors HAViewModel.alertText(for:).
     */
    private fun alertText(entity: HAEntity): String? {
        val name  = entity.displayName
        val state = entity.state.lowercase()

        return when {
            entity.domain == "binary_sensor" -> when {
                entity.deviceClass == "door"     && state == "on"  -> "$name: Open"
                entity.deviceClass == "door"     && state == "off" -> "$name: Closed"
                entity.deviceClass == "window"   && state == "on"  -> "$name: Open"
                entity.deviceClass == "window"   && state == "off" -> "$name: Closed"
                entity.deviceClass == "motion"   && state == "on"  -> "Motion detected: $name"
                entity.deviceClass == "motion"   && state == "off" -> "$name: Clear"
                entity.deviceClass == "smoke"    && state == "on"  -> "Smoke detected: $name!"
                entity.deviceClass == "moisture" && state == "on"  -> "Leak detected: $name!"
                state == "on"  -> "$name: Active"
                state == "off" -> "$name: Inactive"
                else -> null
            }
            entity.domain == "lock"   -> when (state) {
                "locked"   -> "$name: Locked"
                "unlocked" -> "$name: Unlocked"
                else -> null
            }
            entity.domain == "alarm_control_panel" -> "$name: ${entity.displayState}"
            entity.domain == "light",
            entity.domain == "switch",
            entity.domain == "input_boolean" -> when (state) {
                "on"  -> "$name turned on"
                "off" -> "$name turned off"
                else -> null
            }
            entity.domain == "cover" -> when (state) {
                "open"   -> "$name: Open"
                "closed" -> "$name: Closed"
                else -> null
            }
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Periodic status
    // -----------------------------------------------------------------------

    private fun startPeriodicStatus() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                // Only speak periodic status if dashboard is configured
                if (dashboardIds.isNotEmpty()) {
                    speakDashboardStatus()
                }
            }
        }
    }

    /** Build a spoken summary of all pinned entities. */
    fun buildStatusSummary(): String {
        val ids = dashboardIds
        val entities = synchronized(entityCache) {
            if (ids.isEmpty()) {
                entityCache.values.take(5).toList()
            } else {
                ids.mapNotNull { entityCache[it] }
            }
        }
        if (entities.isEmpty()) return "No entities monitored."

        return entities.joinToString(". ") {
            "${it.displayName}: ${it.displayState}"
        } + "."
    }

    private fun speakDashboardStatus() {
        val summary = buildStatusSummary()
        Log.d(TAG, "Status: $summary")
        tts.speak(summary)
    }

    // -----------------------------------------------------------------------
    // Voice command execution
    // -----------------------------------------------------------------------

    private fun startSpeech() {
        speech = SpeechManager(
            context   = this,
            scope     = CoroutineScope(Dispatchers.Main + SupervisorJob()),
            onResult  = ::handleVoiceResult,
            onListening = { Log.d(TAG, "Listening for commands…") }
        )
        // SpeechRecognizer must start on main thread
        serviceScope.launch(Dispatchers.Main) {
            speech.start()
        }
    }

    private fun handleVoiceResult(transcript: String) {
        Log.i(TAG, "Voice: \"$transcript\"")
        val command = cmdHandler.parse(transcript)
        executeCommand(command, transcript)
    }

    private fun executeCommand(command: HACommand, rawTranscript: String) {
        when (command) {
            is HACommand.GetStatus -> {
                speakDashboardStatus()
            }

            is HACommand.CallService -> {
                if (!wsClient.isConnected) {
                    tts.speak("Not connected to Home Assistant.")
                    return
                }
                wsClient.callService(
                    domain    = command.domain,
                    service   = command.service,
                    entityId  = command.entityId,
                    additionalData = command.additionalData
                )
                // Spoken confirmation
                val entityName = command.entityId?.let { id ->
                    synchronized(entityCache) { entityCache[id]?.displayName } ?: id
                } ?: "all ${command.domain}s"

                val verb = when (command.service) {
                    "turn_on"  -> "Turning on"
                    "turn_off" -> "Turning off"
                    "lock"     -> "Locking"
                    "unlock"   -> "Unlocking"
                    else       -> command.service.replace("_", " ").replaceFirstChar { it.uppercase() }
                }
                tts.speak("$verb $entityName.")
            }

            is HACommand.Unknown -> {
                Log.d(TAG, "Unknown command: \"$rawTranscript\"")
                // Silently ignore unknown phrases to avoid constant noise
            }
        }
    }

    // -----------------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Home Assistant Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for HA voice control service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(state: HAConnectionState): Notification {
        val contentText = when (state) {
            HAConnectionState.CONNECTED      -> "Home Assistant connected"
            HAConnectionState.CONNECTING     -> "Connecting to Home Assistant…"
            HAConnectionState.AUTHENTICATING -> "Authenticating…"
            HAConnectionState.RECONNECTING   -> "Reconnecting…"
            HAConnectionState.DISCONNECTED   -> "Disconnected — tap to open"
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HaService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(state: HAConnectionState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(state))
    }

    companion object {
        const val ACTION_STOP = "com.rokid.style.ha.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, HaService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HaService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
