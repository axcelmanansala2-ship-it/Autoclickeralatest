package com.smartsystem.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.smartsystem.autoclicker.databinding.OverlayControlBinding
import com.smartsystem.autoclicker.models.TargetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that:
 *  1. Shows a draggable floating Start/Stop button over all apps
 *  2. When started, runs the detection loop:
 *       screenshot → OCR → find target → accessibility tap → repeat
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlayControlBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var screenCaptureManager: ScreenCaptureManager? = null
    private val detectionEngine = DetectionEngine()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var detectionJob: Job? = null
    private var isDetecting = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WITH_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (data != null) initProjection(resultCode, data)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopDetection()
        detectionEngine.close()
        screenCaptureManager?.release()
        windowManager.removeView(overlayView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay setup ────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        binding = OverlayControlBinding.inflate(LayoutInflater.from(this))
        overlayView = binding.root

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 200
        }

        windowManager.addView(overlayView, layoutParams)
        setupDrag()
        setupButtons()
        updateButtonState()
    }

    /** Make the overlay draggable */
    private fun setupDrag() {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x; initialY = layoutParams.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (touchX - event.rawX).toInt()
                    layoutParams.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        binding.btnToggle.setOnClickListener {
            if (isDetecting) stopDetection() else startDetection()
        }
    }

    private fun updateButtonState() {
        if (isDetecting) {
            binding.btnToggle.text = getString(R.string.btn_stop)
            binding.btnToggle.setBackgroundColor(getColor(R.color.colorOverlayStop))
        } else {
            binding.btnToggle.text = getString(R.string.btn_start)
            binding.btnToggle.setBackgroundColor(getColor(R.color.colorOverlayStart))
        }
    }

    // ─── MediaProjection ─────────────────────────────────────────────────────

    private fun initProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = projectionManager.getMediaProjection(resultCode, data)
        screenCaptureManager = ScreenCaptureManager(this, projection)
        Log.d(TAG, "MediaProjection ready")
    }

    // ─── Detection loop ───────────────────────────────────────────────────────

    private fun startDetection() {
        if (!AutoClickAccessibilityService.isConnected) {
            Toast.makeText(this, getString(R.string.msg_accessibility_needed), Toast.LENGTH_LONG).show()
            return
        }
        val capture = screenCaptureManager
        if (capture == null) {
            Toast.makeText(this, "Screen capture not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val repo = TargetRepository(this)
        val targets = repo.getAll().filter { it.enabled }
        if (targets.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_no_targets), Toast.LENGTH_SHORT).show()
            return
        }

        isDetecting = true
        updateButtonState()
        Toast.makeText(this, getString(R.string.msg_detecting), Toast.LENGTH_SHORT).show()

        detectionJob = scope.launch {
            while (isActive && isDetecting) {
                val bitmap = withContext(Dispatchers.IO) { capture.captureScreen() }
                if (bitmap != null) {
                    val hit = detectionEngine.findFirst(bitmap, targets)
                    if (hit != null) {
                        val (target, point) = hit
                        Log.d(TAG, "Tapping '${target.label}' at ${point.x},${point.y}")
                        AutoClickAccessibilityService.instance?.tap(point.x, point.y)
                        delay(target.delayAfterMs)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopDetection() {
        detectionJob?.cancel()
        detectionJob = null
        isDetecting = false
        updateButtonState()
        Toast.makeText(this, getString(R.string.msg_stopped), Toast.LENGTH_SHORT).show()
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val NOTIF_CHANNEL_ID = "smart_auto_clicker"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 300L

        const val ACTION_START_WITH_PROJECTION = "com.smartsystem.autoclicker.START_PROJECTION"
        const val ACTION_STOP = "com.smartsystem.autoclicker.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }
}
