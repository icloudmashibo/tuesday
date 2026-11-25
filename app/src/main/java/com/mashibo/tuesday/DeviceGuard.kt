package com.mashibo.tuesday

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * Watches the foreground app. When a game (or an app you marked heavy) is running it dims the screen,
 * and dims harder if the phone gets hot. Puts everything back when you leave. Software can't spin a fan,
 * it can only cut the load, so that's all this does.
 */
class DeviceGuard(private val ctx: Context, private val note: (String) -> Unit) {
    private val prefs = Prefs(ctx)
    private var savedBrightness = 128
    private var savedMode = 0
    @Volatile private var active = false
    private var cooled = false
    private var lastFg: String? = null
    private var lastQuery = System.currentTimeMillis() - 15_000

    fun tick() {
        if (!prefs.gameMode) { restore(); return }
        if (!hasUsageAccess(ctx) || !Settings.System.canWrite(ctx)) return
        refreshForeground()
        val pkg = lastFg
        val heavy = pkg != null && isHeavy(pkg)
        if (heavy) {
            if (!active) engage()
            if (!cooled && isHot(ctx)) cool()
        } else if (active) {
            restore()
        }
    }

    private fun refreshForeground() {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        lastQuery = now
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != ctx.packageName) {
                lastFg = e.packageName
            }
        }
    }

    private fun isHeavy(pkg: String): Boolean {
        if (pkg in prefs.heavyApps) return true
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME
        } catch (_: Exception) { false }
    }

    private fun engage() {
        val cr = ctx.contentResolver
        savedMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        savedBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, minOf(savedBrightness, 90))
        active = true
        cooled = false
        note("Game mode: screen dimmed to ease the load.")
    }

    private fun cool() {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 50)
        cooled = true
        note("Phone's running hot. Screen's way down. Take the case off if you can.")
    }

    fun restore() {
        if (!active) return
        val cr = ctx.contentResolver
        try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, savedBrightness)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode)
        } catch (_: Exception) {}
        active = false
        cooled = false
        note("Listening for “Tuesday”")
    }

    companion object {
        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun batteryTempC(ctx: Context): Float {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            return if (t < 0) -1f else t / 10f
        }

        fun isHot(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return true
            }
            return batteryTempC(ctx) >= 41f
        }

        fun setBrightnessPct(ctx: Context, pct: Int): String {
            if (!Settings.System.canWrite(ctx)) return "I need the system settings permission to change brightness."
            val v = (pct.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            return "Brightness is at ${pct.coerceIn(1, 100)} percent."
        }

        fun brightnessPct(ctx: Context): Int =
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255

        fun report(ctx: Context): String {
            val t = batteryTempC(ctx)
            val temp = if (t < 0) "I can't read the battery temperature" else "Battery's at ${"%.1f".format(t)} degrees"
            val state = if (isHot(ctx)) "running warm" else "doing fine"
            val mode = if (Prefs(ctx).gameMode) "Game mode is on." else "Game mode is off."
            return "$temp, and the phone's $state. $mode"
        }
    }
}
