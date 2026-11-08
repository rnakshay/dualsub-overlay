package de.dualsub.overlay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * What the app has been doing, readable on the TV itself.
 *
 * Every event goes to logcat as before and also into a short history the
 * on/off screen shows. The history is kept in files/events.log so it survives
 * a crash or the process being killed -- which is when it is most wanted.
 */
object Diag {

    private const val KEEP = 300
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    // Running figures for the status line.
    @Volatile var translated = 0
        private set
    @Volatile var skipped = 0
        private set
    private val delays = ArrayDeque<Long>()
    @Volatile var deeplUsage: String = ""
    @Volatile var lastError: String = ""
        private set

    /** False from an error until the next line translates; the badge shows it. */
    @Volatile var healthy = true
        private set
    /** Called (on whatever thread) only when [healthy] flips. */
    @Volatile var onHealthChange: ((Boolean) -> Unit)? = null

    private fun setHealthy(ok: Boolean) {
        if (healthy == ok) return
        healthy = ok
        onHealthChange?.invoke(ok)
    }

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "events.log")
        file = f
        if (f.exists()) {
            f.readLines().takeLast(KEEP).forEach { lines.addLast(it) }
            f.writeText(lines.joinToString("\n", postfix = "\n"))   // trim
        }
    }

    fun info(msg: String) = add("  ", msg, Log.INFO)
    fun warn(msg: String) = add("! ", msg, Log.WARN)
    fun error(msg: String, e: Throwable? = null) {
        val text = if (e != null) "$msg: ${e.javaClass.simpleName} ${e.message ?: ""}".trim() else msg
        lastError = "${clock.format(Date())}  $text"
        setHealthy(false)
        add("× ", text, Log.ERROR)
        if (e != null) Log.e(CaptureService.TAG, msg, e)
    }

    /** A line made it to the screen; [delayMs] is German shown -> English shown. */
    @Synchronized
    fun shown(delayMs: Long) {
        translated++
        setHealthy(true)
        delays.addLast(delayMs)
        while (delays.size > 50) delays.removeFirst()
    }

    @Synchronized
    fun skippedRead() { skipped++ }

    @Synchronized
    fun resetSession() {
        translated = 0
        skipped = 0
        delays.clear()
        lastError = ""
        setHealthy(true)
    }

    @Synchronized
    fun medianDelayMs(): Long? = delays.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    @Synchronized
    fun recent(n: Int): List<String> = lines.toList().takeLast(n).asReversed()

    @Synchronized
    private fun add(mark: String, msg: String, level: Int) {
        if (level != Log.ERROR) Log.println(level, CaptureService.TAG, msg)
        val line = "${clock.format(Date())} $mark$msg"
        lines.addLast(line)
        while (lines.size > KEEP) lines.removeFirst()
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }
}
