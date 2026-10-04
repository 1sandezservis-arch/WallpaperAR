package com.example.wallpaperar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.opengl.GLSurfaceView
import com.google.ar.core.*

class MainActivity : Activity() {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var status: TextView
    private var session: Session? = null
    private var userRequestedInstall = true
    private var lastWallState = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = ArRenderer(this)

        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }

        buildUi()
    }

    override fun onResume() {
        super.onResume()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            return
        }

        surfaceView.onResume()
        ensureArCore()
    }

    override fun onPause() {
        surfaceView.onPause()
        session?.pause()
        super.onPause()
    }

    private fun ensureArCore() {
        try {
            if (session == null) {
                status.text = "Перевіряю ARCore…"

                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(this)

                        val config = Config(session).apply {
                            planeFindingMode = Config.PlaneFindingMode.VERTICAL
                            focusMode = Config.FocusMode.AUTO
                        }

                        session!!.configure(config)
                        renderer.attachSession(session!!)
                    }

                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedInstall = false
                        status.text = "Встановлення ARCore…"
                        return
                    }
                }
            }

            session?.resume()
            status.text = "Камера запускається…"
        } catch (e: Exception) {
            renderer.clearSession()
            session?.close()
            session = null

            val name = e.javaClass.simpleName
            val message = e.message?.replace("\n", " ")?.take(180) ?: "без опису"
            status.text = "ARCore: $name\n$message"
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(-1, -1)
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 18, 20, 18)
        }

        status = TextView(this).apply {
            text = "Wallpaper AR"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }

        panel.addView(status, LinearLayout.LayoutParams(-1, 90))

        val hint = TextView(this).apply {
            text = "Наведіть камеру на стіну і повільно рухайте телефоном."
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }

        panel.addView(hint, LinearLayout.LayoutParams(-1, 80))

        val params = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.TOP
        }
        root.addView(panel, params)

        setContentView(root)
    }

    fun setWallStatus(found: Boolean) {
        if (found == lastWallState) return
        lastWallState = found

        runOnUiThread {
            status.text = if (found) {
                "Стіна знайдена ✓"
            } else {
                "Шукаю вертикальну стіну…"
            }
        }
    }

    fun showRendererError(error: Exception) {
        runOnUiThread {
            val name = error.javaClass.simpleName
            val message = error.message?.replace("\n", " ")?.take(160) ?: "без опису"
            status.text = "Помилка рендерингу: $name\n$message"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 10 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            surfaceView.onResume()
            ensureArCore()
        } else if (requestCode == 10) {
            status.text = "Потрібен дозвіл на камеру"
        }
    }

    override fun onDestroy() {
        renderer.clearSession()
        session?.close()
        session = null
        super.onDestroy()
    }
}
