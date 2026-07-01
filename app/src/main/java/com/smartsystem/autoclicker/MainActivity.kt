package com.smartsystem.autoclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartsystem.autoclicker.databinding.ActivityMainBinding

/**
 * Entry point — guides the user through the three required permissions:
 *  1. Display Over Other Apps (SYSTEM_ALERT_WINDOW)
 *  2. Accessibility Service
 *  3. Screen Capture (MediaProjection — requested at "Start Service" time)
 *
 * Once all three are granted, the user can launch the floating overlay service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    // ─── UI setup ─────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnGrantOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnGrantAccessibility.setOnClickListener { requestAccessibilityPermission() }
        binding.btnManageTargets.setOnClickListener {
            startActivity(Intent(this, TargetSetupActivity::class.java))
        }
        binding.btnStartService.setOnClickListener { onStartServiceClicked() }
        binding.btnStopService.setOnClickListener { stopOverlayService() }
    }

    private fun refreshPermissionStatus() {
        val overlayOk = hasOverlayPermission()
        val accessibilityOk = isAccessibilityEnabled()

        binding.iconOverlay.setImageResource(
            if (overlayOk) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )
        binding.iconAccessibility.setImageResource(
            if (accessibilityOk) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )

        val allReady = overlayOk && accessibilityOk
        binding.btnStartService.isEnabled = allReady
        binding.tvStatus.text = if (allReady)
            getString(R.string.status_ready)
        else
            getString(R.string.status_missing)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (allReady) R.color.colorSuccess else R.color.colorWarning
            )
        )
    }

    // ─── Permission helpers ───────────────────────────────────────────────────

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName, ignoreCase = true)
    }

    private fun requestOverlayPermission() {
        if (hasOverlayPermission()) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestAccessibilityPermission() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ─── Service control ──────────────────────────────────────────────────────

    private fun onStartServiceClicked() {
        if (!hasOverlayPermission() || !isAccessibilityEnabled()) {
            Toast.makeText(this, getString(R.string.status_missing), Toast.LENGTH_SHORT).show()
            return
        }
        // Request screen capture permission — triggers system dialog
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                launchOverlayService(resultCode, data)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_START_WITH_PROJECTION
            putExtra(FloatingOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingOverlayService.EXTRA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, getString(R.string.msg_service_started), Toast.LENGTH_SHORT).show()
        finish() // go back to home screen so overlay is visible
    }

    private fun stopOverlayService() {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, getString(R.string.msg_service_stopped), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 100
    }
}
