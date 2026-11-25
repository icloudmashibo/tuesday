package com.mashibo.tuesday

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to Gemini over plain HTTPS. Always call from a background thread. */
object Brain {
    private val history = ArrayList<Pair<String, String>>()

    private fun systemPrompt(p: Prefs): String {
        val who = p.ownerName.ifBlank { "your person" }
        val facts = p.facts().joinToString("; ").ifBlank { "nothing yet" }
        val habits = p.habits(5).joinToString(", ").ifBlank { "none yet" }
        return "You are TUESDAY, $who's personal assistant living on their phone. " +
            "Talk like a sharp, warm friend: short natural sentences, contractions, no lists unless asked. " +
            "Never say you're an AI model and never open with stock phrases like 'Certainly' or 'I'd be happy to'. " +
            "Your replies are spoken out loud, so keep them to two or three sentences. " +
            "If you don't know something, say so plainly. " +
            "You can't press buttons on the phone yourself; the app handles phone commands before they reach you, so never claim you did something. " +
            "Things you've learned about them: $facts. What they ask for most: $habits."
    }

    @Synchronized
    fun ask(ctx: Context, text: String): String {
        val p = Prefs(ctx)
        if (p.apiKey.isBlank()) return "I need your API key first. Put it in the settings and hit save."

        val contents = JSONArray()
        for ((role, t) in history.takeLast(10)) contents.put(turn(role, t))
        contents.put(turn("user", text))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(p)))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.8).put("maxOutputTokens", 300))
            .toString()

        try {
            var (code, resp) = post(p.model, p.apiKey, body)
            if (code == 404 && p.model != "gemini-flash-latest") {
                val r = post("gemini-flash-latest", p.apiKey, body)
                code = r.first; resp = r.second
            }
            if (code == 400 || code == 401 || code == 403) return "Google didn't accept that key. Double-check it in settings."
            if (code == 429) return "I've hit the rate limit. Give me a minute."
            if (code !in 200..299) return "Google had a problem on its end, code $code. Try again in a sec."
            val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            val answer = sb.toString().trim()
            if (answer.isEmpty()) return "I blanked on that one. Ask me again?"
            history.add("user" to text)
            history.add("model" to answer)
            while (history.size > 20) history.removeAt(0)
            return answer
        } catch (e: IOException) {
            return "I can't reach the internet right now."
        } catch (e: Exception) {
            return "Something went sideways reading the answer."
        }
    }

    private fun turn(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun post(model: String, key: String, body: String): Pair<Int, String> {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 25_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to txt
        } finally {
            c.disconnect()
        }
    }
}
