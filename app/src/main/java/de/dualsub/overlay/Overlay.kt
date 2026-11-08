package de.dualsub.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * The English line, drawn over whatever app is playing.
 *
 * Yellow on purpose: CaptureService only treats near-white pixels as subtitle
 * text, so this window -- which the screen capture also sees -- is never read
 * back in as German.
 */
class Overlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var badge: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { view?.text = "" }

    fun attach() = main.post {
        if (view != null) return@post
        val tv = TextView(context).apply {
            setTextColor(COLOUR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT_PX)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(80, 0, 80, 0)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = DEFAULT_BOTTOM_PX
        }
        wm.addView(tv, lp)
        view = tv
        params = lp
        addBadge()
    }

    /** "● dualsub" in the top-left corner: small and faint, just enough to know it is on. */
    private fun addBadge() {
        val tv = TextView(context).apply {
            text = badgeLabel(Diag.healthy)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, BADGE_PX)
            alpha = BADGE_ALPHA
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 24
        }
        wm.addView(tv, lp)
        badge = tv
        // Changes only when an error comes or goes: no polling, no cost.
        Diag.onHealthChange = { ok -> main.post { badge?.text = badgeLabel(ok) } }
    }

    private fun badgeLabel(ok: Boolean) = android.text.SpannableString("●  dualsub").apply {
        setSpan(android.text.style.ForegroundColorSpan(if (ok) BADGE_OK else BADGE_ERROR), 0, 1, 0)
    }

    /** Show [text] with its bottom edge [bottomPx] pixels above the screen's bottom. */
    fun show(text: String, bottomPx: Int) = main.post {
        val tv = view ?: return@post
        val lp = params ?: return@post
        main.removeCallbacks(hideRunnable)
        tv.text = text
        if (lp.y != bottomPx) {
            lp.y = bottomPx
            wm.updateViewLayout(tv, lp)
        }
    }

    fun clear() = main.post {
        main.removeCallbacks(hideRunnable)
        view?.text = ""
    }

    /** German line gone: keep the English up a little longer to finish reading. */
    fun clearAfter(ms: Long) = main.post {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, ms)
    }

    fun detach() = main.post {
        main.removeCallbacks(hideRunnable)
        view?.let { runCatching { wm.removeView(it) } }
        Diag.onHealthChange = null
        badge?.let { runCatching { wm.removeView(it) } }
        view = null
        badge = null
    }

    companion object {
        val COLOUR = Color.rgb(0xFF, 0xE2, 0x7A)
        const val TEXT_PX = 44f
        const val DEFAULT_BOTTOM_PX = 230
        const val BADGE_PX = 20f
        const val BADGE_ALPHA = 0.55f
        val BADGE_OK = Color.rgb(0x5C, 0xD6, 0x7A)
        val BADGE_ERROR = Color.rgb(0xFF, 0x52, 0x52)
    }
}
