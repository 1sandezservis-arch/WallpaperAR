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
            if (event.action == android.view.MotionEvent.ACTION_UP) {
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

    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 14, 18, 0)
        }

        val title = TextView(this).apply {
            text = "WALLPAPER AR"
            setTextColor(Color.WHITE)
            textSize = 23f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        top.addView(title, LinearLayout.LayoutParams(-1, 34))

        status = TextView(this).apply {
            text = "Наведіть камеру на стіну"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setShadowLayer(7f, 0f, 2f, Color.BLACK)
        }
        top.addView(status, LinearLayout.LayoutParams(-1, 30))
        root.addView(top, FrameLayout.LayoutParams(-1, 70).apply { gravity = Gravity.TOP })

        val reticle = TextView(this).apply {
            text = "＋"
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        root.addView(reticle, FrameLayout.LayoutParams(60, 60).apply { gravity = Gravity.CENTER })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 9, 12, 12)
            setBackgroundColor(Color.argb(238, 18, 18, 18))
        }

        val galleryTitle = TextView(this).apply {
            text = "Оберіть шпалери"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        bottom.addView(galleryTitle, LinearLayout.LayoutParams(-1, 25))

        selectedText = TextView(this).apply {
            text = wallpaperNames[selectedWallpaper]
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        bottom.addView(selectedText, LinearLayout.LayoutParams(-1, 23))

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val catalog = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        wallpaperNames.indices.forEach { index ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(2, 2, 2, 2)
                setOnClickListener { selectWallpaper(index) }
            }

            val preview = ImageView(this).apply {
                setImageBitmap(createWallpaperPreview(index))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.DKGRAY)
                contentDescription = wallpaperNames[index]
            }
            item.addView(preview, LinearLayout.LayoutParams(78, 58))

            val number = TextView(this).apply {
                text = (index + 1).toString()
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            item.addView(number, LinearLayout.LayoutParams(78, 16))
            catalog.addView(item, LinearLayout.LayoutParams(82, 78).apply {
                setMargins(2, 0, 2, 0)
            })
        }

        scroll.addView(catalog, HorizontalScrollView.LayoutParams(-2, 78))
        bottom.addView(scroll, LinearLayout.LayoutParams(-1, 80))

        val hint = TextView(this).apply {
            text = "Натисніть на стіну, щоб розмістити"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        bottom.addView(hint, LinearLayout.LayoutParams(-1, 22))

        root.addView(bottom, FrameLayout.LayoutParams(-1, 229).apply {
            gravity = Gravity.BOTTOM
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            bottom.setPadding(12, 9, 12, 12 + nav.bottom)
            insets
        }

        setContentView(root)
    }

    private fun createWallpaperPreview(style: Int): Bitmap {
        val width = 156
        val height = 116
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (style) {
            0 -> {
                canvas.drawColor(Color.rgb(226, 218, 204))
                paint.color = Color.rgb(196, 185, 168)
                for (x in 0 until width step 18) canvas.drawRect(x.toFloat(), 0f, (x + 2).toFloat(), height.toFloat(), paint)
            }
            1 -> {
                canvas.drawColor(Color.rgb(224, 228, 232))
                paint.color = Color.rgb(185, 194, 204)
                paint.strokeWidth = 2f
                for (x in -height until width step 30) {
                    canvas.drawLine(x.toFloat(), 0f, (x + height).toFloat(), height.toFloat(), paint)
                    canvas.drawLine((x + height).toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
                }
            }
            2 -> {
                canvas.drawColor(Color.rgb(157, 151, 141))
                paint.color = Color.rgb(113, 108, 101)
                for (y in 0 until height step 14) canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 2).toFloat(), paint)
                paint.color = Color.rgb(182, 174, 162)
                for (x in 0 until width step 32) canvas.drawRect(x.toFloat(), 0f, (x + 2).toFloat(), height.toFloat(), paint)
            }
            3 -> {
                canvas.drawColor(Color.rgb(177, 197, 176))
                paint.color = Color.rgb(145, 170, 145)
                for (x in 0 until width step 22) canvas.drawRect(x.toFloat(), 0f, (x + 7).toFloat(), height.toFloat(), paint)
            }
            4 -> {
                canvas.drawColor(Color.rgb(188, 184, 177))
                paint.color = Color.rgb(162, 157, 150)
                for (x in 0 until width step 28) canvas.drawRect(x.toFloat(), 0f, (x + 1).toFloat(), height.toFloat(), paint)
                paint.color = Color.rgb(207, 202, 194)
                for (y in 0 until height step 20) canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 1).toFloat(), paint)
            }
            5 -> {
                canvas.drawColor(Color.rgb(232, 229, 222))
                paint.color = Color.rgb(174, 169, 161)
                paint.strokeWidth = 3f
                for (x in -height until width step 38) canvas.drawLine(x.toFloat(), 0f, (x + height).toFloat(), height.toFloat(), paint)
            }
            6 -> {
                canvas.drawColor(Color.rgb(213, 207, 197))
                paint.color = Color.rgb(166, 157, 146)
                for (x in 0 until width step 18) canvas.drawRect(x.toFloat(), 0f, (x + 4).toFloat(), height.toFloat(), paint)
            }
            else -> {
                canvas.drawColor(Color.rgb(173, 139, 104))
                paint.color = Color.rgb(133, 103, 76)
                for (x in 0 until width step 24) canvas.drawRect(x.toFloat(), 0f, (x + 3).toFloat(), height.toFloat(), paint)
                paint.color = Color.rgb(194, 159, 121)
                for (x in 0 until width step 48) canvas.drawCircle(x.toFloat() + 8f, 35f, 2.5f, paint)
            }
        }

        return bitmap
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