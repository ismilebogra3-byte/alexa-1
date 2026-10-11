package com.alexa.assistant

import ai.picovoice.porcupine.Porcupine
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service (type: microphone) = the "always listening" part.
 *
 * Mode A (Picovoice AccessKey set): the word "Alexa" is recognised ON THE PHONE by Porcupine.
 *   Nothing is uploaded until the wake word was heard; then only the command is sent to Groq Whisper.
 * Mode B (no AccessKey): local VAD finds phrases -> Groq Whisper -> phrase must START with "Alexa".
 */
class ListenService : Service() {

    companion object {
        const val FRAME = 512 // 32 ms @ 16 kHz = Porcupine's frame length

        // The phrase must START with "Alexa" (optionally "Hey/Hello Alexa"). Anything else is ignored completely.
        val WAKE = Regex(
            "^[\\s\"'.,!?\\-]*(?:(?:hey|hi|hello|ok|okay|হেই|হ্যালো|হেলো|ওকে)[\\s,.!?]+)?" +
                "(?:alexa|alexia|alexsa|elexa|aleksa|আলেক্সা|এলেক্সা|অ্যালেক্সা|আলেকসা|এলেকসা|অ্যালেকসা)(?![\\p{L}\\p{M}])[\\s,.!?:।\\-]*(.*)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val STOP_RE = Regex("^\\s*(stop|cancel|থামো|থামুন|বন্ধ করো)\\s*[.!?।]*\\s*\$", RegexOption.IGNORE_CASE)
    }

    @Volatile private var running = false
    @Volatile private var rec: AudioRecord? = null
    private var wl: PowerManager.WakeLock? = null
    private var floor = -1.0
    private val frame = ShortArray(FRAME)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val stopPi = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Core.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Alexa")
            .setContentText("শুনছি — \"Alexa\" বলুন")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "Stop", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            Core.listening = true
            Core.stop = false
            Core.status()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alexa:listen")
            wl?.acquire()
            Thread { loop() }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { rec?.stop() } catch (e: Exception) {}
        try { wl?.release() } catch (e: Exception) {}
        Core.listening = false
        Core.status()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- audio helpers

    private fun readFrame(r: AudioRecord): Boolean {
        var got = 0
        while (got < frame.size) {
            val n = r.read(frame, got, frame.size - got)
            if (n <= 0) return false
            got += n
        }
        return true
    }

    private fun trackFloor(level: Double) {
        floor = if (floor < 0) level else if (level < floor) 0.8 * floor + 0.2 * level else floor * 0.999 + level * 0.001
    }

    /** One spoken phrase as PCM. null = timeout/stopped, empty = too short (ignore). */
    private fun nextSegment(r: AudioRecord, timeoutMs: Long): ShortArray? {
        val pre = ArrayDeque<ShortArray>()
        val voiced = ArrayList<ShortArray>()
        var started = false
        var loud = 0
        var quiet = 0
        val t0 = System.currentTimeMillis()
        while (running) {
            if (!readFrame(r)) return null
            if (System.currentTimeMillis() < Core.muteUntil) { // we are talking / just talked
                pre.clear()
                voiced.clear()
                started = false
                loud = 0
                quiet = 0
                continue
            }
            val level = Audio.rms(frame)
            if (floor < 0) floor = level
            val speech = level > maxOf(350.0, floor * 3 + 120)
            val copy = frame.copyOf()
            if (!started) {
                if (!speech) floor = 0.95 * floor + 0.05 * level
                pre.addLast(copy)
                if (pre.size > 10) pre.removeFirst()
                loud = if (speech) loud + 1 else 0
                if (loud >= 3) {
                    started = true
                    voiced.addAll(pre)
                    quiet = 0
                } else if (timeoutMs > 0 && System.currentTimeMillis() - t0 > timeoutMs) {
                    return null
                }
            } else {
                voiced.add(copy)
                quiet = if (speech) 0 else quiet + 1
                if (quiet >= 24 || voiced.size > 8 * 31) break // ~0.77 s silence, or 8 s cap
            }
        }
        if (!running) return null
        if (voiced.size < 17) return ShortArray(0)
        val out = ShortArray(voiced.size * FRAME)
        var o = 0
        for (f in voiced) {
            System.arraycopy(f, 0, out, o, FRAME)
            o += FRAME
        }
        return out
    }

    private fun clean(s: String): String = s.trim().trim(',', '.', '!', '?', ':', '।', '-', ' ')

    /** Removes a leading "Alexa" if the transcript still has one. */
    private fun stripWake(text: String): String {
        val m = WAKE.find(text)
        return clean(if (m != null) m.groupValues[1] else text)
    }

    private fun greetAndListen(r: AudioRecord): String {
        Speech.speakAndWait(Prefs.greeting)
        val t1 = System.currentTimeMillis()
        var seg: ShortArray? = null
        while (running && System.currentTimeMillis() - t1 < 10_000L) {
            seg = nextSegment(r, 10_000L - (System.currentTimeMillis() - t1))
            if (seg == null || seg.isNotEmpty()) break
        }
        val s = seg
        return if (s != null && s.isNotEmpty()) stripWake(Stt.transcribe(s)) else ""
    }

    private fun dispatch(cmdRaw: String) {
        val cmd = clean(cmdRaw)
        if (cmd.isEmpty()) return
        if (STOP_RE.containsMatchIn(cmd)) {
            Core.stop = true
            Speech.stop()
            return
        }
        if (Core.busy.get()) {
            Speech.speakAndWait("আমি এখনো একটা কাজ করছি।")
            return
        }
        Speech.speakAndWait("ঠিক আছে।")
        Core.runTask(cmd)
    }

    // ---------------------------------------------------------------- main loops

    private fun loop() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r: AudioRecord? = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
        } catch (e: Exception) {
            null
        }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Core.log("error", "মাইক খোলা যায়নি (অনুমতি দেওয়া আছে?)")
            stopSelf()
            return
        }
        rec = r
        var porcupine: Porcupine? = null
        try {
            val key = Prefs.picoKey
            if (key.isNotBlank()) {
                try {
                    porcupine = Porcupine.Builder()
                        .setAccessKey(key)
                        .setKeyword(Porcupine.BuiltInKeyword.ALEXA)
                        .build(applicationContext)
                } catch (e: Exception) {
                    Core.log("error", "Picovoice AccessKey/ইঞ্জিন সমস্যা: " + (e.message ?: e.toString()) + " — Whisper মোডে চলছে")
                }
            }
            r.startRecording()
            if (porcupine != null) {
                Core.log("info", "শুনছি — \"Alexa\" বলুন (অফলাইন শব্দ-চেনা: \"Alexa\"-র আগে কিছু আপলোড হয় না)")
                loopOffline(r, porcupine)
            } else {
                Core.log("info", "শুনছি — \"Alexa\" বলুন (Whisper মোড: কথার অংশ Groq-এ আপলোড হয়)")
                loopWhisper(r)
            }
        } catch (e: Exception) {
            Core.log("error", "mic: " + (e.message ?: e.toString()))
        } finally {
            try { r.stop() } catch (e: Exception) {}
            r.release()
            rec = null
            try { porcupine?.delete() } catch (e: Exception) {}
            stopSelf()
        }
    }

    /** Mode A: Porcupine hears "Alexa" locally; only then is anything sent anywhere. */
    private fun loopOffline(r: AudioRecord, p: Porcupine) {
        while (running) {
            if (!readFrame(r)) break
            if (System.currentTimeMillis() < Core.muteUntil) continue
            if (p.process(frame) < 0) {
                trackFloor(Audio.rms(frame))
                continue
            }
            Core.log("heard", "Alexa")
            // user may keep talking: "Alexa, YouTube খোলো"
            val first = nextSegment(r, 1500L)
            var cmd = if (first != null && first.isNotEmpty()) stripWake(Stt.transcribe(first)) else ""
            if (cmd.isEmpty()) cmd = greetAndListen(r) // only "Alexa" -> greet, then listen for the command
            dispatch(cmd)
        }
    }

    /** Mode B: every phrase is transcribed; only phrases that START with "Alexa" are acted on. */
    private fun loopWhisper(r: AudioRecord) {
        while (running) {
            val seg = nextSegment(r, 0L)
            if (seg == null) {
                if (!running) break else continue
            }
            if (seg.isEmpty()) continue
            val text = Stt.transcribe(seg)
            if (text.isBlank()) continue
            val m = WAKE.find(text) ?: continue // not addressed to Alexa -> do nothing, show nothing
            Core.log("heard", text)
            var cmd = clean(m.groupValues[1])
            if (cmd.isEmpty()) cmd = greetAndListen(r)
            dispatch(cmd)
        }
    }
}
