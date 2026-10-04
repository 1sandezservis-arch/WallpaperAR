package com.example.wallpaperar

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import com.google.ar.core.*

class MainActivity : Activity() {
    private var session: Session? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        } else startAr()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply {
            text = "Wallpaper AR"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 100))
        val info = TextView(this).apply {
            text = "Це тест ARCore. Після запуску наведіть телефон на вертикальну стіну."
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }
        root.addView(info, LinearLayout.LayoutParams(-1, 0, 1f))
        val button = Button(this).apply {
            text = "Перевірити ARCore"
            setOnClickListener { startAr() }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, 100))
        setContentView(root)
    }

    private fun startAr() {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            if (availability.isTransient) {
                status.text = "Перевіряю підтримку ARCore…"
                window.decorView.postDelayed({ startAr() }, 1000)
                return
            }
            if (!availability.isSupported) {
                status.text = "ARCore не доступний на цьому телефоні"
                return
            }
            val installStatus = ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                status.text = "Встановіть або оновіть Google Play Services for AR"
                return
            }
            session?.close()
            session = Session(this)
            val config = Config(session).apply {
                planeFindingMode = Config.PlaneFindingMode.VERTICAL
                focusMode = Config.FocusMode.AUTO
            }
            session!!.configure(config)
            session!!.resume()
            status.text = "ARCore працює ✓"
        } catch (e: Exception) {
            status.text = "Помилка ARCore: ${e.javaClass.simpleName}"
        }
    }

    override fun onPause() { super.onPause(); try { session?.pause() } catch (_: Exception) {} }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startAr()
        } else if (requestCode == 10) {
            status.text = "Потрібен дозвіл на камеру"
        }
    }

    override fun onDestroy() { session?.close(); super.onDestroy() }
}
