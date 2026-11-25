package com.mashibo.tuesday

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Routes what you say: phone commands first, then free conversation with the brain. Call from a background thread. */
object Assistant {
    @Volatile private var pending: (() -> String)? = null
    private val yes = setOf("yes", "yeah", "yep", "do it", "go ahead", "sure", "okay", "ok", "confirm")
    private val no = setOf("no", "nope", "cancel", "don't", "stop", "never mind", "nevermind")

    fun handle(ctx: Context, raw: String): String {
        val app = ctx.applicationContext
        val p = Prefs(app)
        var low = raw.trim().lowercase().trimEnd('.', '!', '?', ',')
        if (low.isEmpty()) return "I didn't catch that."

        pending?.let { act ->
            if (low in yes) { pending = null; return act() }
            if (low in no) { pending = null; return "Okay, leaving it alone." }
        }

        val pass = p.passphrase.lowercase()
        val hasPass = pass.isNotBlank() && low.contains(pass)
        if (hasPass) low = low.replace(pass, "").trim()

        fun blocked(): String? {
            val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) return "Unlock your phone first, then ask me again."
            if (pass.isNotBlank() && !hasPass) return "Say your passphrase along with that."
            return null
        }

        // remember something
        Regex("(?i)^remember( that)?\\s+(.+)$").find(raw.trim())?.let {
            p.addFact(it.groupValues[2].trim()); p.bump("remember")
            return "Got it. I'll remember that."
        }

        // game mode
        if (low.contains("game mode")) {
            p.bump("game-mode")
            val off = listOf("off", "disable", "stop").any { low.contains(it) }
            p.gameMode = !off
            return if (off) "Game mode off." else "Game mode on. I'll dim things when you start a game."
        }
        Regex("(?:treat|mark) (.+?) as (?:a )?(?:game|heavy)").find(low)?.let {
            val pkg = findApp(app, it.groupValues[1]) ?: return "I couldn't find an app called ${it.groupValues[1]}."
            p.heavyApps = p.heavyApps + pkg.first
            return "Okay, I'll treat ${pkg.second} as a heavy app."
        }
        if (low.contains("how hot") || low.contains("temperature") || low.contains("thermal") || low.contains("cooling")) {
            p.bump("temperature")
            return DeviceGuard.report(app)
        }

        // brightness
        if (low.contains("brightness") || low.contains("brighter") || low.contains("dimmer") || low.startsWith("dim ")) {
            p.bump("brightness")
            val n = Regex("(\\d{1,3})").find(low)?.groupValues?.get(1)?.toInt()
            val cur = if (Settings.System.canWrite(app)) DeviceGuard.brightnessPct(app) else 50
            return when {
                n != null -> DeviceGuard.setBrightnessPct(app, n)
                low.contains("brighter") || low.contains("up") || low.contains("increase") || low.contains("raise") -> DeviceGuard.setBrightnessPct(app, cur + 20)
                else -> DeviceGuard.setBrightnessPct(app, cur - 20)
            }
        }

        // volume
        if (low.contains("volume") || low.contains("louder") || low.contains("quieter") || low == "mute") {
            p.bump("volume")
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
            val n = Regex("(\\d{1,3})").find(low)?.groupValues?.get(1)?.toInt()
            val target = when {
                low == "mute" || low.contains("mute") -> 0
                n != null -> n
                low.contains("louder") || low.contains("up") || low.contains("raise") || low.contains("increase") -> cur + 15
                else -> cur - 15
            }.coerceIn(0, 100)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max * target / 100, AudioManager.FLAG_SHOW_UI)
            return if (target == 0) "Muted." else "Volume's at $target percent."
        }

        // file tidy-up
        if ((low.contains("organize") || low.contains("organise") || low.contains("clean up") || low.contains("sort")) &&
            (low.contains("download") || low.contains("file"))) {
            p.bump("files")
            blocked()?.let { return it }
            if (!Environment.isExternalStorageManager()) return "I need All files access first. It's in my settings."
            val plan = FileOrganizer.plan()
            if (plan.isEmpty()) return "Nothing to sort. Downloads is already tidy."
            pending = { FileOrganizer.run(app) }
            return "I can sort ${plan.size} files in Downloads into folders by type. Nothing gets deleted. Want me to?"
        }
        if (low.contains("undo") && (low.contains("file") || low.contains("sort") || low.contains("organ") || low == "undo")) {
            blocked()?.let { return it }
            return FileOrganizer.undo(app)
        }

        // lock
        if (low.contains("lock") && (low.contains("phone") || low.contains("screen") || low == "lock" || low.contains("it"))) {
            p.bump("lock")
            blocked()?.let { return it }
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(ComponentName(app, AdminReceiver::class.java))) {
                dpm.lockNow(); return "Locked."
            }
            if (Build.VERSION.SDK_INT >= 28 &&
                PhoneAccessibility.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true) return "Locked."
            return "Turn on device admin or accessibility in my settings and I can lock it."
        }

        // navigation through accessibility
        val nav = when {
            low.contains("go home") || low == "home" -> AccessibilityService.GLOBAL_ACTION_HOME to "Home."
            low.contains("go back") || low == "back" -> AccessibilityService.GLOBAL_ACTION_BACK to "Back."
            low.contains("recent apps") || low.contains("recents") -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Here are your recents."
            low.contains("notifications") && (low.contains("show") || low.contains("open")) -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Notifications."
            low.contains("quick settings") -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Quick settings."
            else -> null
        }
        if (nav != null) {
            p.bump("navigate")
            blocked()?.let { return it }
            val svc = PhoneAccessibility.instance ?: return "Turn on my accessibility service first."
            svc.performGlobalAction(nav.first)
            return nav.second
        }

        // open an app
        Regex("^(?:open|launch|start)\\s+(.+)$").find(low)?.let {
            p.bump("open-app")
            blocked()?.let { b -> return b }
            val name = it.groupValues[1].trim()
            val hit = findApp(app, name) ?: return "I couldn't find an app called $name."
            val launch = app.packageManager.getLaunchIntentForPackage(hit.first) ?: return "I can't open ${hit.second}."
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                app.startActivity(launch); "Opening ${hit.second}."
            } catch (_: Exception) {
                "Android blocked that. Turn on Display over other apps in my settings."
            }
        }

        // everything else is conversation
        p.bump("chat")
        return Brain.ask(app, raw.trim())
    }

    /** Returns (package, label) for the best match on a launcher app name. */
    private fun findApp(ctx: Context, name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return null
        return hit.activityInfo.packageName to hit.loadLabel(pm).toString()
    }
}
