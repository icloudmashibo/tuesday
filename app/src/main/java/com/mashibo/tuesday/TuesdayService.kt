package com.mashibo.tuesday

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ServiceCompat
import java.util.Locale
import java.util.concurrent.Executors

/** Always-on listener. Say "Tuesday" and then your command; for 12 seconds after a reply you can keep talking without the wake word. */
class TuesdayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var awakeUntil = 0L
    private var started = false
    private var guard: DeviceGuard? = null

    private val ticker = object : Runnable {
        override fun run() {
            worker.execute { try { guard?.tick() } catch (_: Exception) {} }
            main.postDelayed(this, 10_000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, 1, buildNote("Listening for “Tuesday”"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!started) {
            started = true
            guard = DeviceGuard(this) { msg -> updateNote(msg) }
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    tts?.setSpeechRate(1.05f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main.post { speaking = false; relisten(300) } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { main.post { speaking = false; relisten(300) } }
                    })
                }
            }
            main.postDelayed(ticker, 3000)
            main.post { listen() }
        }
        return START_STICKY
    }

    private fun buildNote(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tuesday", "TUESDAY", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "tuesday")
            .setContentTitle("TUESDAY")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNote(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNote(text))
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onError(e: Int) {
            if (!speaking) relisten(if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
        }
        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (t.isNullOrBlank()) relisten(300) else heard(t)
        }
    }

    private fun relisten(delayMs: Long) { main.postDelayed({ listen() }, delayMs) }
    private fun relisten(delayMs: Int) = relisten(delayMs.toLong())

    private fun listen() {
        if (speaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNote("This phone has no speech service installed."); return
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            recognizer?.startListening(i)
        } catch (_: Exception) {
            recognizer?.destroy(); recognizer = null
            relisten(1500L)
        }
    }

    private fun heard(text: String) {
        val now = System.currentTimeMillis()
        val woke = Regex("(?i)\\btuesday\\b").containsMatchIn(text)
        if (!woke && now > awakeUntil) { relisten(200L); return }
        val cmd = text.replace(Regex("(?i)\\b(hey |hi |ok |okay )?tuesday\\b[,.!]?"), "").trim()
        awakeUntil = now + 12_000
        Haptics.tick(this)
        if (cmd.isBlank()) { speak("Yeah?"); return }
        worker.execute {
            val reply = try { Assistant.handle(this, cmd) } catch (_: Exception) { "Something tripped on my end." }
            main.post { Haptics.confirm(this); speak(reply) }
        }
    }

    private fun speak(text: String) {
        awakeUntil = System.currentTimeMillis() + 12_000
        val t = tts
        if (t == null || !ttsReady) { relisten(300L); return }
        speaking = true
        recognizer?.cancel()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tuesday")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        worker.execute { try { guard?.restore() } catch (_: Exception) {} }
        worker.shutdown()
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
