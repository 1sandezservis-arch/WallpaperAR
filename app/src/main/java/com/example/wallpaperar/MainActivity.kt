package com.example.wallpaperar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.google.ar.core.*

class MainActivity : Activity() {
    private var session: Session? = null
    private lateinit var status: TextView
    private var userRequestedInstall = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            return
        }

        resumeArCore()
    }

    override fun onPause() {
        session?.pause()
        super.onPause()
    }

    private fun resumeArCore() {
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
                    }

                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedInstall = false
                        status.text = "Встановлення/оновлення ARCore…"
                        return
                    }
                }
            }

            session?.resume()
            status.text = "ARCore працює ✓"
        } catch (e: Exception) {
            session?.close()
            session = null

            val name = e.javaClass.simpleName
            val message = e.message?.replace("\n", " ")?.take(160) ?: "без опису"
            status.text = "ARCore: $name\n$message"
        }
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
        root.addView(status, LinearLayout.LayoutParams(-1, 120))

        val info = TextView(this).apply {
            text = "Тест ARCore\n\nНаведіть телефон на вертикальну стіну."
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }
        root.addView(info, LinearLayout.LayoutParams(-1, 0, 1f))

        val button = Button(this).apply {
            text = "ЗАПУСТИТИ ARCORE"
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    resumeArCore()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
                }
            }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, 100))

        setContentView(root)
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
            resumeArCore()
        } else if (requestCode == 10) {
            status.text = "Потрібен дозвіл на камеру"
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}
