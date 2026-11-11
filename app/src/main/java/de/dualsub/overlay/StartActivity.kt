package de.dualsub.overlay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The app's one screen: a remote-friendly on/off toggle, plus what the app has
 * been doing -- the recent events, newest first, and a status line -- so a
 * problem can be seen on the TV without a laptop and adb.
 *
 * Turning it on returns straight to whatever was playing. From adb:
 *   am start -n de.dualsub.overlay/.StartActivity --es action start|stop
 * With `appops set de.dualsub.overlay PROJECT_MEDIA allow` the screen-capture
 * consent dialog is skipped.
 */
class StartActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var error: TextView
    private lateinit var events: TextView
    private lateinit var toggle: Button
    private lateinit var direction: Button
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this)
        Direction.load(this)
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
        toggle.requestFocus()
        // Show this month's DeepL usage even while the overlay is off.
        if (Diag.deeplUsage.isEmpty()) {
            Thread { Translator(filesDir).warmUp() }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    private fun layout() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.rgb(0x12, 0x12, 0x14))
        setPadding(dp(48), dp(40), dp(48), dp(32))

        // Left: name, state, the toggle.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("DualSub", 34f, Overlay.COLOUR))
            addView(text("English under any app's German subtitles", 13f, GREY).apply {
                setPadding(0, dp(4), 0, dp(28))
            })
            status = text("", 18f, GREY).apply { setPadding(0, 0, 0, dp(16)) }
            addView(status)
            toggle = Button(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                minWidth = dp(180)
                setOnClickListener { onToggle() }
            }
            addView(toggle)
            addView(text("Subtitles", 13f, GREY).apply { setPadding(0, dp(24), 0, dp(4)) })
            // Applies from the next line, no restart needed.
            direction = Button(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minWidth = dp(180)
                isAllCaps = false
                setOnClickListener {
                    Direction.next(context)
                    Diag.info("Subtitles: ${Direction.label()}")
                    refresh()
                }
            }
            addView(direction)
        }, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.MATCH_PARENT))

        // Right: status line, last error, recent events.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), 0, 0, 0)
            stats = text("", 13f, LIGHT).apply { setPadding(0, 0, 0, dp(6)) }
            addView(stats)
            error = text("", 13f, RED).apply { setPadding(0, 0, 0, dp(6)) }
            addView(error)
            addView(text("Recent events, newest first", 11f, GREY).apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            events = text("", 11f, LIGHT).apply {
                typeface = Typeface.MONOSPACE
                setLineSpacing(0f, 1.1f)
            }
            addView(events)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun refresh() {
        val on = CaptureService.running
        status.text = if (on) "● Active" else "Off"
        status.setTextColor(if (on) Overlay.COLOUR else GREY)
        toggle.text = if (on) "Turn off" else "Turn on"
        direction.text = Direction.label()

        val parts = ArrayList<String>()
        if (on || Diag.translated > 0) {
            parts += "${Diag.translated} ${if (Diag.translated == 1) "line" else "lines"} translated"
            Diag.medianDelayMs()?.let { parts += "typical delay %.1f s".format(it / 1000.0) }
            if (Diag.skipped > 0) parts += "${Diag.skipped} unclear ${if (Diag.skipped == 1) "read" else "reads"} skipped"
        }
        if (Diag.deeplUsage.isNotEmpty()) parts += "DeepL ${Diag.deeplUsage} this month"
        stats.text = parts.joinToString("  ·  ")
        error.text = Diag.lastError.let { if (it.isEmpty()) "" else "Last error: $it" }
        events.text = Diag.recent(EVENT_LINES).joinToString("\n")
            .ifEmpty { "Nothing yet. Turn it on and play something with German subtitles." }
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
            Diag.warn("Screen capture refused")
            if (::status.isInitialized) refresh() else finish()
        }
    }

    private fun text(s: String, sp: Float, colour: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(colour)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST = 1
        private const val EVENT_LINES = 24
        private val GREY = Color.rgb(0x9A, 0x9A, 0xA0)
        private val LIGHT = Color.rgb(0xD8, 0xD8, 0xDC)
        private val RED = Color.rgb(0xFF, 0x8A, 0x80)
    }
}
