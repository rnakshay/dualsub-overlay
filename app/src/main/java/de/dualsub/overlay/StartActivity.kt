package de.dualsub.overlay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The app's one screen: a remote-friendly on/off toggle.
 *
 * Turning it on returns straight to whatever was playing. From adb:
 *   am start -n de.dualsub.overlay/.StartActivity --es action start|stop
 * With `appops set de.dualsub.overlay PROJECT_MEDIA allow` the screen-capture
 * consent dialog is skipped.
 */
class StartActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("action")) {
            "stop" -> { stop(); finish(); return }
            "bench" -> {
                startForegroundService(Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_BENCH, true)
                    .putExtra(CaptureService.EXTRA_OMP, intent.getIntExtra(CaptureService.EXTRA_OMP, 2))
                    .putExtra(CaptureService.EXTRA_ENGINES, intent.getIntExtra(CaptureService.EXTRA_ENGINES, 2)))
                finish(); return
            }
            "start" -> { if (!CaptureService.running) requestCapture() else finish(); return }
        }
        setContentView(layout())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun layout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(0x12, 0x12, 0x14))
        addView(TextView(context).apply {
            text = "DualSub"
            setTextColor(Overlay.COLOUR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "English under any app's German subtitles"
            setTextColor(Color.rgb(0x9A, 0x9A, 0xA0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 40)
        })
        status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        addView(status)
        toggle = Button(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minWidth = 360
            setOnClickListener { onToggle() }
        }
        addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun refresh() {
        val on = CaptureService.running
        status.text = if (on) "● Active" else "Off"
        status.setTextColor(if (on) Overlay.COLOUR else Color.rgb(0x9A, 0x9A, 0xA0))
        toggle.text = if (on) "Turn off" else "Turn on"
        toggle.requestFocus()
    }

    private fun onToggle() {
        if (CaptureService.running) {
            stop()
            // The service finishes tearing down on its own thread.
            main.postDelayed({ refresh() }, 400)
        } else {
            requestCapture()
        }
    }

    private fun stop() = stopService(Intent(this, CaptureService::class.java))

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("Activity result API is overkill for one request")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            intent.extras?.let { extras ->
                if (extras.containsKey(CaptureService.EXTRA_SCALE))
                    service.putExtra(CaptureService.EXTRA_SCALE, extras.getFloat(CaptureService.EXTRA_SCALE))
                if (extras.containsKey(CaptureService.EXTRA_OMP))
                    service.putExtra(CaptureService.EXTRA_OMP, extras.getInt(CaptureService.EXTRA_OMP))
                if (extras.containsKey(CaptureService.EXTRA_ENGINES))
                    service.putExtra(CaptureService.EXTRA_ENGINES, extras.getInt(CaptureService.EXTRA_ENGINES))
            }
            startForegroundService(service)
            finish()        // back to whatever was playing
        } else {
            Log.w(CaptureService.TAG, "screen capture refused")
            if (::status.isInitialized) refresh() else finish()
        }
    }

    companion object {
        private const val REQUEST = 1
    }
}
