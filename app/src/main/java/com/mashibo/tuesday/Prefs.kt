package com.mashibo.tuesday

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything is saved instantly with apply(), so Save can never hang. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tuesday", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) { sp.edit().putString("apiKey", v.trim()).apply() }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { sp.edit().putString("model", v.trim().ifBlank { DEFAULT_MODEL }).apply() }

    var ownerName: String
        get() = sp.getString("owner", "") ?: ""
        set(v) { sp.edit().putString("owner", v.trim()).apply() }

    var passphrase: String
        get() = sp.getString("pass", "") ?: ""
        set(v) { sp.edit().putString("pass", v.trim()).apply() }

    var gameMode: Boolean
        get() = sp.getBoolean("gameMode", true)
        set(v) { sp.edit().putBoolean("gameMode", v).apply() }

    var heavyApps: Set<String>
        get() = sp.getStringSet("heavy", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("heavy", v).apply() }

    fun facts(): List<String> {
        val a = JSONArray(sp.getString("facts", "[]"))
        return List(a.length()) { a.getString(it) }
    }

    fun addFact(f: String) {
        val l = facts().toMutableList()
        if (l.none { it.equals(f, true) }) l.add(f)
        sp.edit().putString("facts", JSONArray(l.takeLast(60)).toString()).apply()
    }

    fun bump(key: String) {
        val o = JSONObject(sp.getString("usage", "{}"))
        o.put(key, o.optInt(key, 0) + 1)
        sp.edit().putString("usage", o.toString()).apply()
    }

    fun habits(n: Int): List<String> {
        val o = JSONObject(sp.getString("usage", "{}"))
        return o.keys().asSequence().sortedByDescending { o.getInt(it) }.take(n).toList()
    }

    companion object { const val DEFAULT_MODEL = "gemini-3.8-flash" }
}
