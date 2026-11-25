package com.mashibo.tuesday

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val rows = ArrayList<Triple<MaterialButton, String, () -> Boolean>>()

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        if (micGranted()) startVoice()
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(48))
        }
        setContentView(ScrollView(this).apply { addView(content) })

        content.addView(TextView(this).apply {
            text = "T.U.E.S.D.A.Y."
            textSize = 30f
            setTextColor(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorPrimary, 0))
        })
        status = TextView(this).apply { textSize = 15f; text = "Fill in the basics, allow what you want me to handle, then hit Save." }
        content.addView(status, lp(top = 8))

        val name = field("Your name", prefs.ownerName)
        val key = field("Gemini API key", prefs.apiKey, secret = true)
        val model = field("Model", prefs.model)
        val pass = field("Passphrase for sensitive commands (optional)", prefs.passphrase, secret = true)

        section("Permissions")
        perm("Microphone", { micGranted() }) {
            val list = arrayListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            askPerms.launch(list.toTypedArray())
        }
        perm("Usage access (so I can spot games)", { DeviceGuard.hasUsageAccess(this) }) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        perm("Change system settings (brightness)", { Settings.System.canWrite(this) }) { open(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }
        perm("All files access (tidy Downloads)", { Environment.isExternalStorageManager() }) { open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true) }
        perm("Display over other apps (open apps by voice)", { Settings.canDrawOverlays(this) }) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
        perm("Accessibility (Home, Back, Recents)", { PhoneAccessibility.instance != null }) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        perm("Device admin (lock the screen)", { adminOn() }) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java)))
        }

        val save = MaterialButton(this).apply {
            text = "Save and wake TUESDAY"
            setOnClickListener {
                Haptics.confirm(this@MainActivity)
                prefs.ownerName = name.text.toString()
                prefs.apiKey = key.text.toString()
                prefs.model = model.text.toString()
                prefs.passphrase = pass.text.toString()
                if (micGranted()) {
                    startVoice()
                    status.text = "Saved. Say “Tuesday” and talk to me."
                } else {
                    status.text = "Saved. Allow the microphone and I'll start listening."
                    askPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            }
        }
        content.addView(save, lp(top = 20))

        section("Try me without talking")
        val say = field("Type something", "")
        val reply = TextView(this).apply { textSize = 16f }
        val send = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Send"
            setOnClickListener {
                val t = say.text.toString()
                if (t.isBlank()) return@setOnClickListener
                Haptics.tick(this@MainActivity)
                reply.text = "…"
                Thread {
                    val r = try { Assistant.handle(this@MainActivity, t) } catch (_: Exception) { "Something tripped on my end." }
                    runOnUiThread { reply.text = r; Haptics.confirm(this@MainActivity) }
                }.start()
            }
        }
        content.addView(send, lp(top = 8))
        content.addView(reply, lp(top = 12))
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun startVoice() {
        try { ContextCompat.startForegroundService(this, Intent(this, TuesdayService::class.java)) } catch (_: Exception) {
            status.text = "Android wouldn't start the listener. Open the app and try Save again."
        }
    }

    private fun adminOn(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun open(action: String, withPackage: Boolean = false) {
        val i = Intent(action)
        if (withPackage) i.data = Uri.parse("package:$packageName")
        try { startActivity(i) } catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun lp(top: Int = 12) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title; textSize = 18f
            setTextColor(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorPrimary, 0))
        }, lp(top = 28))
    }

    private fun field(hint: String, value: String, secret: Boolean = false): TextInputEditText {
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        layout.hint = hint
        if (secret) layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val edit = TextInputEditText(layout.context)
        edit.setText(value)
        edit.setSingleLine()
        if (secret) edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(edit)
        content.addView(layout, lp(top = 12))
        return edit
    }

    private fun perm(label: String, granted: () -> Boolean, action: () -> Unit) {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.setOnClickListener { Haptics.tick(this); action() }
        content.addView(b, lp(top = 8))
        rows.add(Triple(b, label, granted))
    }

    private fun refresh() {
        for ((b, label, granted) in rows) {
            val ok = try { granted() } catch (_: Exception) { false }
            b.text = if (ok) "✓  $label" else label
            b.alpha = if (ok) 0.6f else 1f
        }
    }
}
