package com.example.wallpaperar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import android.opengl.GLSurfaceView
import com.google.ar.core.*

class MainActivity : Activity() {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var status: TextView
    private lateinit var selectedText: TextView
    private var session: Session? = null
    private var userRequestedInstall = true
    private var selectedWallpaper = 0
    private var lastStatus = ""

    private val wallpaperNames = arrayOf(
        "Бежевий льон",
        "Світла геометрія",
        "Лофт",
        "Зелений мінімалізм"
    )
    private val wallpaperPrices = intArrayOf(399, 449, 499, 429)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = ArRenderer(this)
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }

        surfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                renderer.requestPlacement(event.x, event.y)
            }
            true
        }

        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            return
        }
        if (ensureArCore()) surfaceView.onResume()
    }

    override fun onPause() {
        surfaceView.onPause()
        session?.pause()
        super.onPause()
    }

    private fun ensureArCore(): Boolean {
        try {
            if (session == null) {
                status.text = "Підготовка AR…"
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(this)
                        val config = Config(session).apply {
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                            focusMode = Config.FocusMode.AUTO
                            if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                depthMode = Config.DepthMode.AUTOMATIC
                            }
                        }
                        session!!.configure(config)
                        renderer.attachSession(session!!)
                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedInstall = false
                        status.text = "Встановлення ARCore…"
                        return false
                    }
                }
            }
            session?.resume()
            return true
        } catch (e: Exception) {
            renderer.clearSession()
            session?.close()
            session = null
            status.text = "ARCore: ${e.javaClass.simpleName}"
            return false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        val title = TextView(this).apply {
            text = "WALLPAPER AR"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        }
        top.addView(title, LinearLayout.LayoutParams(-1, dp(34)))

        status = TextView(this).apply {
            text = "Наведіть камеру на стіну"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        }
        top.addView(status, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(top, FrameLayout.LayoutParams(-1, dp(82)).apply { gravity = Gravity.TOP })

        val reticle = TextView(this).apply {
            text = "＋"
            setTextColor(Color.WHITE)
            textSize = 38f
            gravity = Gravity.CENTER
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        }
        root.addView(reticle, FrameLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.argb(235, 18, 18, 18))
        }

        selectedText = TextView(this).apply {
            text = selectedLabel()
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bottom.addView(selectedText, LinearLayout.LayoutParams(-1, dp(30)))

        val catalog = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        wallpaperNames.indices.forEach { index ->
            val card = TextView(this).apply {
                text = "${index + 1}\n${wallpaperNames[index]}"
                textSize = 11f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(3, 3, 3, 3)
                setBackgroundColor(
                    when (index) {
                        0 -> Color.rgb(224, 216, 202)
                        1 -> Color.rgb(220, 225, 232)
                        2 -> Color.rgb(150, 145, 135)
                        else -> Color.rgb(165, 190, 165)
                    }
                )
                setOnClickListener { selectWallpaper(index) }
            }
            catalog.addView(card, LinearLayout.LayoutParams(0, 62, 1f).apply {
                setMargins(5, 0, 5, 0)
            })
        }

        bottom.addView(catalog, LinearLayout.LayoutParams(-1, 70))

        val place = Button(this).apply {
            text = "РОЗМІСТИТИ НА СТІНІ"
            textSize = 13f
            minHeight = 0
            minWidth = 0
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(255, 196, 0))
            setOnClickListener { renderer.requestPlacement(surfaceView.width / 2f, surfaceView.height / 2f) }
        }
        bottom.addView(place, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(dp(2), dp(6), dp(2), 0)
        })

        val clear = TextView(this).apply {
            text = "Очистити"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            setOnClickListener { renderer.clearWallpaper() }
        }
        bottom.addView(clear, LinearLayout.LayoutParams(-1, dp(24)).apply {
            setMargins(dp(2), dp(2), dp(2), 0)
        })

        root.addView(bottom, FrameLayout.LayoutParams(-1, dp(194)).apply {
            gravity = Gravity.BOTTOM
        })
        setContentView(root)
    }

    private fun selectWallpaper(index: Int) {
        selectedWallpaper = index
        renderer.setWallpaper(index)
        selectedText.text = selectedLabel()
    }

    private fun selectedLabel(): String =
        "${wallpaperNames[selectedWallpaper]} • від ${wallpaperPrices[selectedWallpaper]} грн/рулон"

    fun setArStatus(message: String) {
        if (message == lastStatus) return
        lastStatus = message
        runOnUiThread { status.text = message }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (ensureArCore()) surfaceView.onResume()
        } else if (requestCode == 10) {
            status.text = "Для роботи потрібен доступ до камери"
        }
    }

    override fun onDestroy() {
        renderer.clearSession()
        session?.close()
        session = null
        super.onDestroy()
    }
}