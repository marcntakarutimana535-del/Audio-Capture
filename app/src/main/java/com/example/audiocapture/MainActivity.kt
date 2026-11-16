package com.example.audiocapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val REQ_PERM = 1
    private val REQ_PROJECTION = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply {
            text = "Prêt. Lancez la capture, puis jouez l'audio dans l'autre application."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        val start = Button(this).apply {
            text = "Démarrer la capture"
            setOnClickListener { askPermissionsThenStart() }
        }
        val stop = Button(this).apply {
            text = "Arrêter et enregistrer"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                status.text = "Capture arrêtée. Fichiers dans Musique/AudioCapture"
            }
        }
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val last = getSharedPreferences("diag", MODE_PRIVATE).getString("last", null)
        if (last != null) status.text = last
    }

    private fun askPermissionsThenStart() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERM)
        } else {
            requestProjection()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERM) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                requestProjection()
            } else {
                status.text = "Permission audio refusée (nécessaire pour la capture interne)."
            }
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                startForegroundService(i)
                status.text = "En attente de son… Chaque audio est enregistré automatiquement (nouveau fichier après 5 s de silence). Appuyez sur « Arrêter » pour terminer."
            } else {
                status.text = "Autorisation de capture refusée."
            }
        }
    }
}
