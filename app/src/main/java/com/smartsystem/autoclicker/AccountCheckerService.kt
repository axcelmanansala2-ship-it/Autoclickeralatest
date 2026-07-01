package com.smartsystem.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.smartsystem.autoclicker.databinding.OverlayCheckerBinding
import com.smartsystem.autoclicker.models.AccountRepository
import com.smartsystem.autoclicker.models.AccountStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * COD Mobile Account Checker Service.
 *
 * Automation loop:
 *  1. Reset any stuck IN_PROGRESS → PENDING (crash recovery).
 *  2. Get next PENDING account.
 *  3. Find "GARENA" button and tap it (or launch CODM app as fallback).
 *  4. Wait for the Garena login screen.
 *  5. Fill username + password via fillTextField().
 *  6. Tap "Login Now".
 *  7. Quick check (5 s) for "Login failed" → INVALID → next account.
 *  8. Long check (25 s) for result:
 *     - "violated" / "abnormal" → BANNED → dismiss dialog → next account
 *     - "CREATE CHARACTER"      → NEW    → dismiss dialog → next account
 *     - Timeout (25 s)          → GOOD   → go home → next account
 *
 * Dialog dismissal uses tapByText("OK") first, then pressBack() as fallback
 * since Unity game dialogs may not expose buttons in the accessibility tree.
 */
class AccountCheckerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlayCheckerBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var repo: AccountRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var checkerJob: Job? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        repo = AccountRepository(this)
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> startChecker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopChecker()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay ──────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        binding = OverlayCheckerBinding.inflate(LayoutInflater.from(this))
        overlayView = binding.root

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 160
        }

        windowManager.addView(overlayView, layoutParams)

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        overlayView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { initX = layoutParams.x; initY = layoutParams.y; touchX = ev.rawX; touchY = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> { layoutParams.x = initX + (ev.rawX - touchX).toInt(); layoutParams.y = initY + (ev.rawY - touchY).toInt(); windowManager.updateViewLayout(overlayView, layoutParams); true }
                else -> false
            }
        }

        binding.btnCheckerToggle.setOnClickListener {
            if (isRunning) stopChecker() else startChecker()
        }
        updateOverlay("Ready", "", "")
    }

    private fun updateOverlay(status: String, account: String, counts: String) {
        binding.btnCheckerToggle.text = if (isRunning) "STOP" else "START"
        binding.btnCheckerToggle.setBackgroundColor(
            if (isRunning) getColor(R.color.colorOverlayStop) else getColor(R.color.colorCheckerStart)
        )
        binding.tvCheckerStatus.text = status
        binding.tvCheckerAccount.text = account
        binding.tvCheckerCounts.text = counts
    }

    private fun buildCountsText(): String {
        val all = repo.getAll()
        val p = all.count { it.status == AccountStatus.PENDING }
        val b = all.count { it.status == AccountStatus.BANNED }
        val n = all.count { it.status == AccountStatus.NEW_ACCOUNT }
        val g = all.count { it.status == AccountStatus.GOOD }
        val inv = all.count { it.status == AccountStatus.INVALID }
        return "P:$p B:$b N:$n G:$g F:$inv"
    }

    // ─── Dialog dismissal ─────────────────────────────────────────────────────

    /**
     * Dismisses a game dialog. Tries accessibility tap on "OK" first;
     * falls back to pressBack() since Unity game dialogs may not expose buttons.
     */
    private suspend fun dismissDialog(svc: AutoClickAccessibilityService) {
        // Try tapping OK button via accessibility
        val okPoint = withContext(Dispatchers.IO) { svc.findNodeCenter("OK") }
        if (okPoint != null) {
            svc.tap(okPoint.x, okPoint.y)
            delay(600)
        } else {
            // Fallback: BACK button dismisses most game dialogs
            svc.pressBack()
            delay(600)
        }
        // Double-check: if dialog still showing, press BACK again
        val stillShowing = withContext(Dispatchers.IO) {
            svc.hasAnyText("Attention", "violated", "abnormal", "CREATE CHARACTER")
        }
        if (stillShowing != null) {
            svc.pressBack()
            delay(500)
        }
    }

    // ─── Checker loop ─────────────────────────────────────────────────────────

    private fun startChecker() {
        if (!AutoClickAccessibilityService.isConnected) {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
            return
        }

        // Guard: prevent launching a second coroutine while one is already running
        if (isRunning) return

        // Reset stuck IN_PROGRESS accounts back to PENDING (crash/stop recovery)
        repo.resetInProgress()

        if (repo.getNextPending() == null) {
            Toast.makeText(this, "No pending accounts to check", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = true
        updateOverlay("Starting…", "", buildCountsText())

        checkerJob = scope.launch {
            while (isActive && isRunning) {
                val account = repo.getNextPending()
                if (account == null) {
                    withContext(Dispatchers.Main) {
                        updateOverlay("Done! ✓", "All checked", buildCountsText())
                        isRunning = false
                    }
                    break
                }

                repo.setStatus(account.id, AccountStatus.IN_PROGRESS)
                val shortUser = account.username.take(14)

                // ── Step 1: Open GARENA ──────────────────────────────────────
                withContext(Dispatchers.Main) { updateOverlay("Opening GARENA…", shortUser, buildCountsText()) }

                val svc = AutoClickAccessibilityService.instance
                if (svc == null) {
                    repo.setStatus(account.id, AccountStatus.PENDING)
                    withContext(Dispatchers.Main) { updateOverlay("Waiting for accessibility…", shortUser, buildCountsText()) }
                    delay(3000)
                    continue
                }

                // CODM is a Unity game — its main-screen buttons (GUEST/GARENA/Facebook)
                // are rendered via OpenGL and are NOT in the Android accessibility tree.
                // Strategy:
                //  1. Try accessibility first (works if Unity exposes nodes on this device)
                //  2. Launch CODM if not already foreground, wait for it to load
                //  3. Retry accessibility after load
                //  4. Fall back to a proportional coordinate tap at the GARENA button position
                var garenaPoint = withContext(Dispatchers.IO) { svc.findNodeCenter("GARENA") }

                if (garenaPoint == null) {
                    // Bring CODM to foreground (or launch it)
                    val launched = withContext(Dispatchers.Main) { tryLaunchCODM() }
                    if (!launched) {
                        Log.w(TAG, "Could not launch CODM")
                        repo.setStatus(account.id, AccountStatus.PENDING)
                        delay(3000)
                        continue
                    }

                    // Wait for the game main menu to fully render (Unity can be slow)
                    withContext(Dispatchers.Main) { updateOverlay("Waiting CODM…", shortUser, buildCountsText()) }
                    delay(5000)

                    // Retry accessibility after the game has loaded
                    garenaPoint = withContext(Dispatchers.IO) { svc.findNodeCenter("GARENA") }
                }

                if (garenaPoint != null) {
                    Log.d(TAG, "GARENA found via accessibility at $garenaPoint")
                    svc.tap(garenaPoint.x, garenaPoint.y)
                } else {
                    // Unity UI not in accessibility tree — tap at proportional screen coordinate.
                    // In landscape CODM, GARENA is the center button of 3 login options,
                    // positioned at ~50% width and ~81% height of the display.
                    val (sw, sh) = withContext(Dispatchers.Main) { getScreenSize() }
                    val gx = sw * 0.50f
                    val gy = sh * 0.81f
                    Log.d(TAG, "GARENA not in tree — coordinate tap at ($gx, $gy) on ${sw}x${sh}")
                    withContext(Dispatchers.Main) { updateOverlay("Tapping GARENA…", shortUser, buildCountsText()) }
                    svc.tap(gx, gy)
                }

                // ── Step 2: Wait for Garena native login screen ───────────────
                // The Garena login IS a native Android activity, so accessibility
                // can find its text fields once it opens.
                withContext(Dispatchers.Main) { updateOverlay("Waiting login…", shortUser, buildCountsText()) }

                var loginReady = false
                var loginPollCount = 0
                while (!loginReady && loginPollCount < 40 && isActive) {  // max 20s
                    val found = withContext(Dispatchers.IO) {
                        svc.hasAnyText("Garena Username", "Login Now", "Email or Phone")
                    }
                    if (found != null) {
                        loginReady = true
                    } else {
                        loginPollCount++
                        // After 8 seconds (16 polls) with no login screen, retry the GARENA tap
                        if (loginPollCount == 16) {
                            Log.d(TAG, "Login screen not found after 8s — retrying GARENA tap")
                            val retryPoint = withContext(Dispatchers.IO) { svc.findNodeCenter("GARENA") }
                            if (retryPoint != null) {
                                svc.tap(retryPoint.x, retryPoint.y)
                            } else {
                                val (sw, sh) = withContext(Dispatchers.Main) { getScreenSize() }
                                svc.tap(sw * 0.50f, sh * 0.81f)
                            }
                        }
                        delay(500)
                    }
                }

                if (!loginReady) {
                    Log.w(TAG, "Login screen not found after 20s")
                    repo.setStatus(account.id, AccountStatus.PENDING)
                    delay(2000)
                    continue
                }

                delay(500)

                // ── Step 3: Fill username ─────────────────────────────────────
                withContext(Dispatchers.Main) { updateOverlay("Filling creds…", shortUser, buildCountsText()) }

                val filled = withContext(Dispatchers.IO) {
                    svc.fillTextField("Garena Username", account.username)
                        || svc.fillTextField("Email or Phone", account.username)
                }
                if (!filled) Log.w(TAG, "Username field not found for ${account.username}")

                delay(400)

                // ── Step 4: Fill password ─────────────────────────────────────
                withContext(Dispatchers.IO) { svc.fillTextField("Password", account.password) }

                delay(400)

                // ── Step 5: Tap Login Now ─────────────────────────────────────
                withContext(Dispatchers.Main) { updateOverlay("Logging in…", shortUser, buildCountsText()) }

                val loginPoint = withContext(Dispatchers.IO) { svc.findNodeCenter("Login Now") }
                loginPoint?.let { svc.tap(it.x, it.y) }

                delay(800)

                // ── Step 6a: Quick check for "Login failed" (5 s) ─────────────
                withContext(Dispatchers.Main) { updateOverlay("Checking…", shortUser, buildCountsText()) }

                var resultHandled = false
                repeat(10) {  // 10 × 500ms = 5s
                    if (!resultHandled) {
                        val hit = withContext(Dispatchers.IO) {
                            svc.hasAnyText("Login failed", "incorrect", "invalid")
                        }
                        if (hit != null) {
                            Log.d(TAG, "INVALID: ${account.username} → $hit")
                            repo.setStatus(account.id, AccountStatus.INVALID, "Login failed: wrong credentials")
                            withContext(Dispatchers.Main) { updateOverlay("Invalid ✗", shortUser, buildCountsText()) }
                            delay(800)
                            resultHandled = true
                        } else {
                            delay(500)
                        }
                    }
                }

                if (resultHandled) { delay(1000); continue }

                // ── Step 6b: Long check for ban/new/good (25 s) ───────────────
                repeat(50) {  // 50 × 500ms = 25s
                    if (!resultHandled) {
                        val hit = withContext(Dispatchers.IO) {
                            svc.hasAnyText("violated", "abnormal", "CREATE CHARACTER", "Login failed", "incorrect")
                        }
                        when {
                            hit != null && (hit.contains("violated") || hit.contains("abnormal")) -> {
                                Log.d(TAG, "BANNED: ${account.username}")
                                repo.setStatus(account.id, AccountStatus.BANNED, hit)
                                withContext(Dispatchers.Main) { updateOverlay("Banned ✗", shortUser, buildCountsText()) }
                                dismissDialog(svc)
                                resultHandled = true
                            }
                            hit != null && hit.contains("create character") -> {
                                Log.d(TAG, "NEW ACCOUNT: ${account.username}")
                                repo.setStatus(account.id, AccountStatus.NEW_ACCOUNT)
                                withContext(Dispatchers.Main) { updateOverlay("New acct ◆", shortUser, buildCountsText()) }
                                dismissDialog(svc)
                                resultHandled = true
                            }
                            hit != null && (hit.contains("login failed") || hit.contains("incorrect")) -> {
                                Log.d(TAG, "INVALID (late): ${account.username}")
                                repo.setStatus(account.id, AccountStatus.INVALID, "Login failed")
                                withContext(Dispatchers.Main) { updateOverlay("Invalid ✗", shortUser, buildCountsText()) }
                                delay(800)
                                resultHandled = true
                            }
                            else -> delay(500)
                        }
                    }
                }

                // ── Timeout → GOOD (reached lobby) ────────────────────────────
                if (!resultHandled) {
                    Log.d(TAG, "GOOD: ${account.username}")
                    repo.setStatus(account.id, AccountStatus.GOOD)
                    withContext(Dispatchers.Main) { updateOverlay("Good ✓", shortUser, buildCountsText()) }
                    svc.pressHome()
                    delay(2500)
                }

                delay(1200)
            }
        }
    }

    private fun stopChecker() {
        // Set isRunning = false FIRST so the loop condition fails immediately
        isRunning = false
        checkerJob?.cancel()
        checkerJob = null
        // Reset any IN_PROGRESS back to PENDING so next start picks them up
        if (::repo.isInitialized) repo.resetInProgress()
        updateOverlay("Stopped", "", buildCountsText())
    }

    // ─── Screen size helper ───────────────────────────────────────────────────

    /** Returns (width, height) of the default display in pixels. Must run on Main. */
    private fun getScreenSize(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(size)
            Pair(size.x.toFloat(), size.y.toFloat())
        }
    }

    // ─── App launch fallback ──────────────────────────────────────────────────

    private fun tryLaunchCODM(): Boolean {
        val codmPackages = listOf(
            "com.activision.callofduty.shooter",
            "com.garena.game.codm",
            "com.tencent.ig"
        )
        for (pkg in codmPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            return true
        }
        val pm = packageManager
        val match = pm.getInstalledApplications(PackageManager.GET_META_DATA).firstOrNull { app ->
            try {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label.contains("call of duty") || label.contains("garena")
            } catch (_: Exception) { false }
        }
        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return true
        }
        return false
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Account Checker", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AccountCheckerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("COD Account Checker")
            .setContentText("Running account verification loop")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "AccountCheckerService"
        private const val NOTIF_ID = 1002
        private const val NOTIF_CHANNEL = "account_checker"
        const val ACTION_START = "com.smartsystem.autoclicker.CHECKER_START"
        const val ACTION_STOP = "com.smartsystem.autoclicker.CHECKER_STOP"
    }
}
