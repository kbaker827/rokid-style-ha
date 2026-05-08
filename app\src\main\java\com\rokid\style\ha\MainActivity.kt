package com.rokid.style.ha

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.style.ha.databinding.ActivityMainBinding

private const val TAG                   = "MainActivity"
private const val REQUEST_AUDIO_PERM    = 100
private const val BIND_RETRY_DELAY_MS   = 1000L

/**
 * Minimal launcher activity — no display on Rokid glasses so the UI is
 * kept deliberately simple. Its only jobs are:
 *  1. Request RECORD_AUDIO permission on first launch.
 *  2. Start [HaService] as a foreground service.
 *  3. Show basic status in the (optional) on-device preview screen.
 *  4. Provide a Settings button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Service binding (optional — used to pull live status for the display)
    private var haService: HaService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
            val binder = iBinder as? HaService.LocalBinder ?: return
            haService    = binder.getService()
            serviceBound = true
            refreshStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            haService    = null
            serviceBound = false
        }
    }

    // Handler for periodic status refresh on the display
    private val handler     = Handler(Looper.getMainLooper())
    private val statusTick  = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 5000)
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnStartService.setOnClickListener {
            if (checkAudioPermission()) {
                startHaService()
            }
        }

        binding.btnStopService.setOnClickListener {
            HaService.stop(this)
            binding.tvStatus.text = getString(R.string.status_stopped)
        }

        // Auto-start service if configured
        if (Prefs.isConfigured(this)) {
            if (checkAudioPermission()) {
                startHaService()
            }
        } else {
            binding.tvStatus.text = getString(R.string.status_not_configured)
        }
    }

    override fun onResume() {
        super.onResume()
        // Bind to service for live status updates
        Intent(this, HaService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        handler.post(statusTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTick)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    private fun checkAudioPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERM
            )
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERM &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startHaService()
        } else {
            Toast.makeText(this, R.string.perm_audio_denied, Toast.LENGTH_LONG).show()
            binding.tvStatus.text = getString(R.string.perm_audio_denied)
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun startHaService() {
        HaService.start(this)
        binding.tvStatus.text = getString(R.string.status_connecting)
    }

    private fun refreshStatus() {
        val summary = haService?.buildStatusSummary() ?: return
        binding.tvDashboard.text = summary
    }
}
