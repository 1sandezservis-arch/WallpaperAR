package com.example.wallpaperar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(private val activity: MainActivity) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private var cameraTextureId = 0
    private var cameraProgram = 0
    private var wallpaperProgram = 0
    private val wallpaperTextures = IntArray(8)

    private var cameraPosition = 0
    private var cameraTexCoord = 0
    private var wallPosition = 0
    private var wallTexCoord = 0
    private var wallMvp = 0
    private var wallSampler = 0
    private var wallAlpha = 0

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val ndcCoords = floatArrayOf(
        -1f, -1f, 1f, -1f,
        -1f,  1f, 1f,  1f
    )
    private val cameraTexCoords = FloatArray(8)
    private val ndcBuffer = floatBuffer(ndcCoords)
    private val cameraTexBuffer = floatBuffer(cameraTexCoords)

    private val wallVertices = FloatArray(12)
    private val wallTexCoords = FloatArray(8)
    private val wallBuffer = floatBuffer(wallVertices)
    private val wallTexBuffer = floatBuffer(wallTexCoords)

    @Volatile private var surfaceReady = false
    @Volatile private var placementRequested = false
    @Volatile private var requestedX = 0f
    @Volatile private var requestedY = 0f
    @Volatile private var selectedWallpaper = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var anchor: Anchor? = null
    // Dimensions captured from the detected vertical wall plane at placement time.
    private var wallWidthMeters = 0f
    private var wallHeightMeters = 0f
    private var lastStatus = ""
    private var trackingSinceMs = 0L
    private var pausedSinceMs = 0L
    private var stableTracking = false

    fun attachSession(value: Session) {
        session = value
        if (surfaceReady && cameraTextureId != 0) value.setCameraTextureName(cameraTextureId)
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            value.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation, surfaceWidth, surfaceHeight)
        }
    }

    fun clearSession() {
        anchor?.detach()
        anchor = null
        wallWidthMeters = 0f
        wallHeightMeters = 0f
        session = null
    }

    fun setWallpaper(index: Int) {
        selectedWallpaper = index.coerceIn(0, wallpaperTextures.lastIndex)
    }

    fun requestPlacement(x: Float, y: Float) {
        requestedX = x
        requestedY = y
        placementRequested = true
    }

    fun clearWallpaper() {
        anchor?.detach()
        anchor = null
        wallWidthMeters = 0f
        wallHeightMeters = 0f
        placementRequested = false
        activity.setArStatus("Наведіть камеру на стіну та натисніть на екран")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        cameraPosition = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        cameraTexCoord = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")

        wallpaperProgram = createProgram(WALL_VERTEX_SHADER, WALL_FRAGMENT_SHADER)
        wallPosition = GLES20.glGetAttribLocation(wallpaperProgram, "a_Position")
        wallTexCoord = GLES20.glGetAttribLocation(wallpaperProgram, "a_TexCoord")
        wallMvp = GLES20.glGetUniformLocation(wallpaperProgram, "u_Mvp")
        wallSampler = GLES20.glGetUniformLocation(wallpaperProgram, "u_Texture")
        wallAlpha = GLES20.glGetUniformLocation(wallpaperProgram, "u_Alpha")

        val cameraTexture = IntArray(1)
        GLES20.glGenTextures(1, cameraTexture, 0)
        cameraTextureId = cameraTexture[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        for (i in wallpaperTextures.indices) {
            wallpaperTextures[i] = createWallpaperTexture(i)
        }

        surfaceReady = true
        session?.setCameraTextureName(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!surfaceReady) return

        try {
            val frame = s.update()
            drawCamera(frame)
            updateStableTrackingStatus(frame.camera)

            // ARCore can briefly switch between TRACKING and PAUSED while
            // refining the pose. Do not expose those frame-to-frame changes
            // directly in the UI, otherwise the status flickers several times
            // per second. Require a stable state before changing the message.
            // Work like a camera mask: show the selected wallpaper immediately,
            // while ARCore keeps searching for a real wall in the background.
            if (anchor == null && frame.camera.trackingState == TrackingState.TRACKING) {
                autoPlaceDetectedWall(frame)
            }

            if (placementRequested) {
                placementRequested = false
                placeFromHitTest(frame, requestedX, requestedY)
            }

                if (anchor == null) drawScreenWallpaperPreview()
            drawWallpaper(frame)
        } catch (e: Exception) {
            activity.setArStatus("AR помилка: ${e.javaClass.simpleName}")
        }
    }


    private fun updateStableTrackingStatus(camera: Camera) {
        val now = android.os.SystemClock.elapsedRealtime()

        if (camera.trackingState == TrackingState.TRACKING) {
            if (trackingSinceMs == 0L) trackingSinceMs = now
            pausedSinceMs = 0L

            // Once tracking is stable, keep the "ready" state through short
            // ARCore pauses caused by normal pose refinement.
            if (!stableTracking && now - trackingSinceMs >= 700L) {
                stableTracking = true
                activity.setArStatus("AR готовий ✓ — наведіть хрестик на стіну")
            }
        } else {
            if (pausedSinceMs == 0L) pausedSinceMs = now
            trackingSinceMs = 0L

            // Only leave "ready" after a real, sustained tracking loss.
            if (stableTracking && now - pausedSinceMs < 1500L) return

            if (!stableTracking && now - pausedSinceMs < 500L) return

            stableTracking = false
            val reason = camera.trackingFailureReason.toString()
            val message = when (reason) {
                "INSUFFICIENT_LIGHT" -> "Замало світла — наведіть на освітлену стіну"
                "INSUFFICIENT_FEATURES" -> "Мало деталей — повільно рухайте телефоном по стіні"
                "EXCESSIVE_MOTION" -> "Рух надто швидкий — рухайте телефоном повільніше"
                else -> "AR ще калібрується — повільно рухайте телефоном"
            }
            activity.setArStatus(message)
        }
    }

    private fun autoPlaceDetectedWall(frame: Frame) {
        // Prefer the vertical plane directly under the screen reticle. This prevents
        // ARCore from selecting a different wall simply because it is larger.
        val centerX = surfaceWidth / 2f
        val centerY = surfaceHeight / 2f
        val hitPlane = frame.hitTest(centerX, centerY).firstOrNull { hit ->
            val plane = hit.trackable as? Plane
            plane != null &&
                plane.trackingState == TrackingState.TRACKING &&
                plane.type == Plane.Type.VERTICAL &&
                plane.isPoseInPolygon(hit.hitPose)
        }?.trackable as? Plane

        val wallPlane = hitPlane ?: sTrackedVerticalPlanes(frame)
            .filter { calculateDistanceToPlane(it.centerPose, frame.camera.getPose()) > 0f }
            .maxByOrNull { it.extentX * it.extentZ }
            ?: return

        if (wallPlane.extentX < 0.5f || wallPlane.extentZ < 0.5f) return
        anchor?.detach()
        anchor = wallPlane.createAnchor(wallPlane.centerPose)
        wallWidthMeters = wallPlane.extentX
        wallHeightMeters = wallPlane.extentZ
        activity.setArStatus("Стіна знайдена ✓ — шпалери розміщено")
    }

    private fun placeFromHitTest(frame: Frame, x: Float, y: Float) {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            activity.setArStatus("Наведіть камеру на стіну")
            return
        }

        val hits = frame.hitTest(x, y)
        var selectedHit: HitResult? = hits.firstOrNull {
            val trackable = it.trackable
            trackable is Plane &&
                trackable.trackingState == TrackingState.TRACKING &&
                trackable.type == Plane.Type.VERTICAL &&
                trackable.isPoseInPolygon(it.hitPose)
        }

        // If the reticle is not exactly over the detected plane polygon,
        // use the largest tracked vertical plane instead. This makes wall
        // placement reliable even when the user presses slightly off-center.
        if (selectedHit == null) {
            val wallPlane = sTrackedVerticalPlanes(frame).maxByOrNull { it.extentX * it.extentZ }
            if (wallPlane != null) {
                anchor?.detach()
                anchor = wallPlane.createAnchor(wallPlane.centerPose)
                wallWidthMeters = wallPlane.extentX
                wallHeightMeters = wallPlane.extentZ
                activity.setArStatus("Стіна знайдена ✓  • шпалери розміщено")
                return
            }
        }

        if (selectedHit == null) {
            selectedHit = hits.firstOrNull { it.trackable is DepthPoint }
        }

        if (selectedHit == null) {
            selectedHit = hits.firstOrNull {
                val trackable = it.trackable
                trackable is Point &&
                    trackable.trackingState == TrackingState.TRACKING &&
                    trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
        }

        // Instant Placement fallback: place immediately, then let ARCore refine the pose.
        if (selectedHit == null) {
            val instantHit = frame.hitTestInstantPlacement(x, y, 1.5f).firstOrNull()
            if (instantHit != null) {
                anchor?.detach()
                anchor = instantHit.createAnchor()
                wallWidthMeters = 2.0f
                wallHeightMeters = 2.7f
                activity.setArStatus("Шпалери розміщено • калібрую поверхню…")
                return
            }
        }

        if (selectedHit == null) {
            activity.setArStatus("Наведіть камеру на стіну")
            return
        }

        anchor?.detach()
        anchor = selectedHit.createAnchor()
        val hitPlane = selectedHit.trackable as? Plane
        wallWidthMeters = hitPlane?.extentX?.takeIf { it > 0.5f } ?: 2.0f
        wallHeightMeters = hitPlane?.extentZ?.takeIf { it > 0.5f } ?: 2.7f
        activity.setArStatus("Шпалери розміщено ✓")
    }

    private fun sTrackedVerticalPlanes(frame: Frame): List<Plane> =
        session?.getAllTrackables(Plane::class.java)?.filter {
            it.trackingState == TrackingState.TRACKING &&
                it.type == Plane.Type.VERTICAL &&
                it.extentX > 0.3f &&
                it.extentZ > 0.3f
        } ?: emptyList()

    private fun drawCamera(frame: Frame) {
        ndcBuffer.rewind()
        ndcBuffer.put(ndcCoords)
        ndcBuffer.rewind()

        cameraTexBuffer.rewind()
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndcCoords,
            Coordinates2d.TEXTURE_NORMALIZED,
            cameraTexCoords
        )
        cameraTexBuffer.put(cameraTexCoords)
        cameraTexBuffer.rewind()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(cameraProgram)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "u_Texture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        GLES20.glEnableVertexAttribArray(cameraPosition)
        GLES20.glVertexAttribPointer(cameraPosition, 2, GLES20.GL_FLOAT, false, 0, ndcBuffer)
        GLES20.glEnableVertexAttribArray(cameraTexCoord)
        GLES20.glVertexAttribPointer(cameraTexCoord, 2, GLES20.GL_FLOAT, false, 0, cameraTexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(cameraPosition)
        GLES20.glDisableVertexAttribArray(cameraTexCoord)
    }

    private fun drawScreenWallpaperPreview() {
        // Fallback mask: the user sees the selected wallpaper immediately,
        // just like a live AR filter, even before a physical wall is tracked.
        val left = -0.78f
        val right = 0.78f
        val bottom = -0.70f
        val top = 0.70f
        wallVertices[0] = left
        wallVertices[1] = bottom
        wallVertices[2] = 0f
        wallVertices[3] = right
        wallVertices[4] = bottom
        wallVertices[5] = 0f
        wallVertices[6] = left
        wallVertices[7] = top
        wallVertices[8] = 0f
        wallVertices[9] = right
        wallVertices[10] = top
        wallVertices[11] = 0f

        wallTexCoords[0] = 0f
        wallTexCoords[1] = 1f
        wallTexCoords[2] = 1f
        wallTexCoords[3] = 1f
        wallTexCoords[4] = 0f
        wallTexCoords[5] = 0f
        wallTexCoords[6] = 1f
        wallTexCoords[7] = 0f

        wallBuffer.rewind()
        wallBuffer.put(wallVertices)
        wallBuffer.rewind()
        wallTexBuffer.rewind()
        wallTexBuffer.put(wallTexCoords)
        wallTexBuffer.rewind()

        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(wallpaperProgram)
        GLES20.glUniformMatrix4fv(wallMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(wallAlpha, 0.48f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallpaperTextures[selectedWallpaper])
        GLES20.glUniform1i(wallSampler, 1)
        GLES20.glEnableVertexAttribArray(wallPosition)
        GLES20.glVertexAttribPointer(wallPosition, 3, GLES20.GL_FLOAT, false, 0, wallBuffer)
        GLES20.glEnableVertexAttribArray(wallTexCoord)
        GLES20.glVertexAttribPointer(wallTexCoord, 2, GLES20.GL_FLOAT, false, 0, wallTexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(wallPosition)
        GLES20.glDisableVertexAttribArray(wallTexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawWallpaper(frame: Frame) {
        val a = anchor ?: return
        if (a.trackingState != TrackingState.TRACKING) {
            if (lastStatus != "anchor-paused") {
                lastStatus = "anchor-paused"
                activity.setArStatus("Поверніть камеру до розміщених шпалер")
            }
            return
        }

        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100f)
        a.pose.toMatrix(modelMatrix, 0)

        // ARCore plane coordinates are X/Z on the plane; Y is the plane normal.
        // The previous implementation used X/Y, which made a vertical wall
        // render as a skewed/rotated rectangle (visible in the supplied video).
        // Match the detected wall's actual dimensions instead of using a fixed
        // 2.4 x 2.7 m quad that could spill outside the wall.
        val width = (wallWidthMeters.takeIf { it > 0.5f } ?: 2.0f) * 0.98f
        val height = (wallHeightMeters.takeIf { it > 0.5f } ?: 2.7f) * 0.98f
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val normalOffset = 0.006f

        wallVertices[0] = -halfWidth
        wallVertices[1] = normalOffset
        wallVertices[2] = -halfHeight
        wallVertices[3] = halfWidth
        wallVertices[4] = normalOffset
        wallVertices[5] = -halfHeight
        wallVertices[6] = -halfWidth
        wallVertices[7] = normalOffset
        wallVertices[8] = halfHeight
        wallVertices[9] = halfWidth
        wallVertices[10] = normalOffset
        wallVertices[11] = halfHeight

        val repeatX = width / 0.55f
        val repeatY = height / 0.55f
        wallTexCoords[0] = 0f
        wallTexCoords[1] = repeatY
        wallTexCoords[2] = repeatX
        wallTexCoords[3] = repeatY
        wallTexCoords[4] = 0f
        wallTexCoords[5] = 0f
        wallTexCoords[6] = repeatX
        wallTexCoords[7] = 0f

        wallBuffer.rewind()
        wallBuffer.put(wallVertices)
        wallBuffer.rewind()
        wallTexBuffer.rewind()
        wallTexBuffer.put(wallTexCoords)
        wallTexBuffer.rewind()

        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        GLES20.glUseProgram(wallpaperProgram)
        GLES20.glUniformMatrix4fv(wallMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(wallAlpha, 0.94f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallpaperTextures[selectedWallpaper])
        GLES20.glUniform1i(wallSampler, 1)

        GLES20.glEnableVertexAttribArray(wallPosition)
        GLES20.glVertexAttribPointer(wallPosition, 3, GLES20.GL_FLOAT, false, 0, wallBuffer)
        GLES20.glEnableVertexAttribArray(wallTexCoord)
        GLES20.glVertexAttribPointer(wallTexCoord, 2, GLES20.GL_FLOAT, false, 0, wallTexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(wallPosition)
        GLES20.glDisableVertexAttribArray(wallTexCoord)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun createWallpaperTexture(style: Int): Int {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (style) {
            0 -> {
                canvas.drawColor(Color.rgb(226, 218, 204))
                paint.color = Color.rgb(196, 185, 168)
                for (x in 0 until size step 36) canvas.drawRect(x.toFloat(), 0f, (x + 4).toFloat(), size.toFloat(), paint)
            }
            1 -> {
                canvas.drawColor(Color.rgb(224, 228, 232))
                paint.color = Color.rgb(185, 194, 204)
                paint.strokeWidth = 4f
                for (x in -size until size * 2 step 70) {
                    canvas.drawLine(x.toFloat(), 0f, (x + size).toFloat(), size.toFloat(), paint)
                    canvas.drawLine((x + size).toFloat(), 0f, x.toFloat(), size.toFloat(), paint)
                }
            }
            2 -> {
                canvas.drawColor(Color.rgb(157, 151, 141))
                paint.color = Color.rgb(113, 108, 101)
                for (y in 0 until size step 42) canvas.drawRect(0f, y.toFloat(), size.toFloat(), (y + 5).toFloat(), paint)
                paint.color = Color.rgb(182, 174, 162)
                for (x in 0 until size step 95) canvas.drawRect(x.toFloat(), 0f, (x + 3).toFloat(), size.toFloat(), paint)
            }
            3 -> {
                canvas.drawColor(Color.rgb(177, 197, 176))
                paint.color = Color.rgb(145, 170, 145)
                for (x in 0 until size step 54) canvas.drawRect(x.toFloat(), 0f, (x + 18).toFloat(), size.toFloat(), paint)
                paint.color = Color.rgb(200, 214, 195)
                for (x in 0 until size step 54) canvas.drawRect((x + 18).toFloat(), 0f, (x + 25).toFloat(), size.toFloat(), paint)
            }
            4 -> {
                canvas.drawColor(Color.rgb(188, 184, 177))
                paint.color = Color.rgb(162, 157, 150)
                for (x in 0 until size step 56) canvas.drawRect(x.toFloat(), 0f, (x + 2).toFloat(), size.toFloat(), paint)
                paint.color = Color.rgb(207, 202, 194)
                for (y in 0 until size step 40) canvas.drawRect(0f, y.toFloat(), size.toFloat(), (y + 2).toFloat(), paint)
            }
            5 -> {
                canvas.drawColor(Color.rgb(232, 229, 222))
                paint.color = Color.rgb(174, 169, 161)
                paint.strokeWidth = 6f
                for (x in -size until size * 2 step 76) canvas.drawLine(x.toFloat(), 0f, (x + size).toFloat(), size.toFloat(), paint)
            }
            6 -> {
                canvas.drawColor(Color.rgb(213, 207, 197))
                paint.color = Color.rgb(166, 157, 146)
                for (x in 0 until size step 36) canvas.drawRect(x.toFloat(), 0f, (x + 8).toFloat(), size.toFloat(), paint)
            }
            else -> {
                canvas.drawColor(Color.rgb(173, 139, 104))
                paint.color = Color.rgb(133, 103, 76)
                for (x in 0 until size step 48) canvas.drawRect(x.toFloat(), 0f, (x + 6).toFloat(), size.toFloat(), paint)
                paint.color = Color.rgb(194, 159, 121)
                for (x in 0 until size step 96) canvas.drawCircle(x.toFloat() + 20f, 110f, 6f, paint)
            }
        }

        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bitmap.recycle()
        return texture[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("OpenGL link error: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("OpenGL shader error: $log")
        }
        return shader
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    companion object {
        private const val CAMERA_VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        private const val WALL_VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_Mvp * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val WALL_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Alpha;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = vec4(texture2D(u_Texture, v_TexCoord).rgb, u_Alpha);
            }
        """
    }
}