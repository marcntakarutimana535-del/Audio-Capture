package com.example.audiocapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val SAMPLE_RATE = 44100
        private const val SILENCE_THRESHOLD = 200      // amplitude (0-32767) en dessous = silence
        private const val SILENCE_TIMEOUT_MS = 5000L   // durée de silence avant fermeture du fichier en cours
    }

    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!running) {
                    val code = intent.getIntExtra(EXTRA_CODE, 0)
                    @Suppress("DEPRECATION")
                    val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                    if (data != null) startCapture(code, data)
                }
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Capture audio", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("Capture audio en cours")
            .setContentText("En attente de son…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        // Doit être appelé AVANT getMediaProjection (Android 14+)
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, null)
        projection = proj

        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, 16384)

        @Suppress("MissingPermission")
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        running = true
        worker = Thread {
            val pcm = ShortArray(bufBytes / 2)
            val left = ShortArray(pcm.size / 2)
            val right = ShortArray(pcm.size / 2)
            val mp3 = ByteArray((7200 + pcm.size * 1.25).toInt())

            var lame: AndroidLame? = null
            var uri: Uri? = null
            var out: OutputStream? = null
            var silentSince = 0L   // 0 = pas en silence
            var totalBytes = 0L
            var filesCount = 0
            var maxPeak = 0
            var errorMsg = "aucune"

            // Termine proprement le fichier en cours (si il y en a un) et remet tout à zéro
            // pour que le prochain son démarre automatiquement un NOUVEAU fichier.
            fun finishFile() {
                val l = lame
                val o = out
                try {
                    if (l != null && o != null) {
                        val tail = l.flush(mp3)
                        if (tail > 0) { o.write(mp3, 0, tail); totalBytes += tail }
                    }
                } catch (e: Throwable) {
                    errorMsg = e.toString()
                }
                try { o?.flush(); o?.close() } catch (_: Exception) {}
                try { l?.close() } catch (_: Exception) {}
                uri?.let {
                    val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    contentResolver.update(it, done, null, null)
                }
                if (o != null) filesCount++
                lame = null
                out = null
                uri = null
                silentSince = 0L
            }

            try {
                record.startRecording()
                while (running) {
                    val read = record.read(pcm, 0, pcm.size)
                    if (read <= 0) continue

                    // Détection du son : amplitude max du bloc
                    var peak = 0
                    for (i in 0 until read) {
                        val v = Math.abs(pcm[i].toInt())
                        if (v > peak) peak = v
                    }
                    if (peak > maxPeak) maxPeak = peak
                    val hasSound = peak > SILENCE_THRESHOLD
                    val now = System.currentTimeMillis()

                    if (out == null) {
                        // En attente du début de l'audio : rien n'est écrit
                        if (!hasSound) continue
                        val name = "capture_" +
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp3"
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AudioCapture")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                        uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        out = uri?.let { contentResolver.openOutputStream(it) }
                        if (out == null) break
                        // Nouvel encodeur MP3 pour chaque nouveau fichier
                        lame = LameBuilder()
                            .setInSampleRate(SAMPLE_RATE)
                            .setOutSampleRate(SAMPLE_RATE)
                            .setOutChannels(2)
                            .setOutBitrate(192)
                            .setMode(LameBuilder.Mode.STEREO)
                            .setQuality(2)
                            .build()
                        silentSince = 0L
                        updateNotification("Enregistrement en cours…")
                    }

                    if (hasSound) silentSince = 0L
                    else if (silentSince == 0L) silentSince = now

                    val frames = read / 2
                    for (i in 0 until frames) {
                        left[i] = pcm[2 * i]
                        right[i] = pcm[2 * i + 1]
                    }
                    val n = lame!!.encode(left, right, frames, mp3)
                    if (n > 0) { out!!.write(mp3, 0, n); totalBytes += n }
                    else if (n < 0) errorMsg = "encode=" + n

                    // 5 s de silence : on ferme ce fichier, mais la capture continue.
                    // Le prochain son créera automatiquement un nouveau fichier.
                    if (silentSince != 0L && now - silentSince >= SILENCE_TIMEOUT_MS) {
                        finishFile()
                        updateNotification("En attente de son…")
                    }
                }
            } catch (e: Throwable) {
                errorMsg = e.toString()
            } finally {
                // Arrêt manuel (ou erreur) : on ferme le fichier éventuellement en cours
                finishFile()
                getSharedPreferences("diag", MODE_PRIVATE).edit().putString("last",
                    "Dernier essai : " + filesCount + " fichier(s), " + (totalBytes / 1024) +
                    " Ko écrits, volume max " + maxPeak + "/32767, erreur : " + errorMsg).apply()
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }.also { it.start() }
    }

    private fun updateNotification(text: String) {
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("Capture audio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notif)
    }

    private fun stopCapture() {
        running = false
        try { worker?.join(3000) } catch (_: Exception) {}
        worker = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
