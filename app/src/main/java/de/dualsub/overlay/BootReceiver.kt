package de.dualsub.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Turns the overlay back on after a reboot, if that is switched on.
 *
 * Screen capture can only be asked for from an activity, so this opens
 * [StartActivity] in its no-screen "start" mode. A background app may not open
 * activities on Android 10+, except one allowed to draw over other apps --
 * which this one is. With PROJECT_MEDIA allowed, no consent dialog shows
 * either: the activity starts the service and closes straight away.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Autostart.enabled(context) || CaptureService.running) return
        Diag.init(context)
        Diag.info("Starting after reboot")
        context.startActivity(Intent(context, StartActivity::class.java)
            .putExtra("action", "start")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }
}

/** Whether to turn the overlay on after a reboot. Off by default. */
object Autostart {

    private const val PREFS = "settings"
    private const val KEY = "autostart"

    fun enabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun toggle(context: Context): Boolean {
        val on = !enabled(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        return on
    }

    fun label(context: Context) = if (enabled(context)) "On" else "Off"
}
