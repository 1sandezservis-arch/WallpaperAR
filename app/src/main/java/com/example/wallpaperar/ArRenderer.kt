package com.example.wallpaperar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val activity: MainActivity
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private var cameraTextureId = 0
    private var cameraProgram = 0
    private var planeProgram = 0
    private var wallpaperTextureId = 0

    private var cameraPosition = 0
    private var cameraTexCoord = 0
    private var planePosition = 0
    private var planeColor = 0
    private var planeMvp = 0
    private var wallpaperSampler = 0
    private var planeTexCoord = 0

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val ndcCoords = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f
    )

    private val cameraTexCoords = FloatArray(8)
    private val ndcBuffer: FloatBuffer = floatBuffer(ndcCoords)
    private val texBuffer: FloatBuffer = floatBuffer(cameraTexCoords)

    private val planeVertices = FloatArray(12)
    private val planeTexCoords = FloatArray(8)
    private val planeBuffer: FloatBuffer = floatBuffer(planeVertices)
    private val planeTexBuffer: FloatBuffer = floatBuffer(planeTexCoords)

    @Volatile
    private var surfaceReady = false

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    fun attachSession(value: Session) {
        session = value
        if (surfaceReady && cameraTextureId != 0) {
            value.setCameraTextureName(cameraTextureId)
        }
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            val rotation = activity.windowManager.defaultDisplay.rotation
            value.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight)
        }
    }

    fun clearSession() {
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        cameraPosition = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        cameraTexCoord = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")

        planeProgram = createProgram(PLANE_VERTEX_SHADER, PLANE_FRAGMENT_SHADER)
        planePosition = GLES20.glGetAttribLocation(planeProgram, "a_Position")
        planeTexCoord = GLES20.glGetAttribLocation(planeProgram, "a_TexCoord")
        planeColor = GLES20.glGetUniformLocation(planeProgram, "u_Color")
        planeMvp = GLES20.glGetUniformLocation(planeProgram, "u_Mvp")
        wallpaperSampler = GLES20.glGetUniformLocation(planeProgram, "u_Texture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        wallpaperTextureId = createWallpaperTexture()

        surfaceReady = true
        session?.setCameraTextureName(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)

        val s = session ?: return
        val rotation = activity.windowManager.defaultDisplay.rotation
        s.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val s = session ?: return
        if (!surfaceReady) return

        try {
            val frame = s.update()
            drawCamera(frame)
            drawDetectedWall(frame)
        } catch (e: Exception) {
            activity.showRendererError(e)
        }
    }

    private fun drawCamera(frame: Frame) {
        ndcBuffer.clear()
        ndcBuffer.put(ndcCoords)
        ndcBuffer.position(0)

        texBuffer.clear()

        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndcCoords,
            Coordinates2d.TEXTURE_NORMALIZED,
            cameraTexCoords
        )

        texBuffer.put(cameraTexCoords)
        texBuffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(cameraProgram)

        // The external camera sampler must read from texture unit 0.
        val textureUniform = GLES20.glGetUniformLocation(cameraProgram, "u_Texture")
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        GLES20.glEnableVertexAttribArray(cameraPosition)
        GLES20.glVertexAttribPointer(cameraPosition, 2, GLES20.GL_FLOAT, false, 0, ndcBuffer)

        GLES20.glEnableVertexAttribArray(cameraTexCoord)
        GLES20.glVertexAttribPointer(cameraTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(cameraPosition)
        GLES20.glDisableVertexAttribArray(cameraTexCoord)
    }

    
    private fun sTrackedVerticalPlane(frame: Frame): Plane? {
        val s = session ?: return null
        return s.getAllTrackables(Plane::class.java).firstOrNull {
            it.trackingState == TrackingState.TRACKING &&
            it.type == Plane.Type.VERTICAL &&
            it.subsumedBy == null
        }
    }

    private fun drawDetectedWall(frame: Frame) {
        // Use all known planes, not only planes updated in this frame.
        // A tracked wall can remain valid for many frames without being "updated".
        val plane = sTrackedVerticalPlane(frame)
            ?: frame.getUpdatedTrackables(Plane::class.java).firstOrNull {
                it.trackingState == TrackingState.TRACKING &&
                it.type == Plane.Type.VERTICAL &&
                it.subsumedBy == null
            }

        if (plane == null) {
            activity.setWallStatus(false)
            return
        }

        activity.setWallStatus(true)

        val halfX = plane.extentX.coerceAtMost(4f) / 2f
        val halfZ = plane.extentZ.coerceAtMost(4f) / 2f

        planeVertices[0] = -halfX
        planeVertices[1] = 0f
        planeVertices[2] = -halfZ
        planeVertices[3] = halfX
        planeVertices[4] = 0f
        planeVertices[5] = -halfZ
        planeVertices[6] = -halfX
        planeVertices[7] = 0f
        planeVertices[8] = halfZ
        planeVertices[9] = halfX
        planeVertices[10] = 0f
        planeVertices[11] = halfZ

        val uMax = (halfX * 2f).coerceAtLeast(0.1f)
        val vMax = (halfZ * 2f).coerceAtLeast(0.1f)
        planeTexCoords[0] = 0f
        planeTexCoords[1] = vMax
        planeTexCoords[2] = uMax
        planeTexCoords[3] = vMax
        planeTexCoords[4] = 0f
        planeTexCoords[5] = 0f
        planeTexCoords[6] = uMax
        planeTexCoords[7] = 0f

        planeBuffer.rewind()
        planeBuffer.put(planeVertices)
        planeBuffer.rewind()
        planeTexBuffer.rewind()
        planeTexBuffer.put(planeTexCoords)
        planeTexBuffer.rewind()

        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100f)
        plane.centerPose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(planeProgram)
        GLES20.glUniformMatrix4fv(planeMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(planeColor, 1f, 1f, 1f, 1f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallpaperTextureId)
        GLES20.glUniform1i(wallpaperSampler, 1)

        GLES20.glEnableVertexAttribArray(planePosition)
        GLES20.glVertexAttribPointer(planePosition, 3, GLES20.GL_FLOAT, false, 0, planeBuffer)
        GLES20.glEnableVertexAttribArray(planeTexCoord)
        GLES20.glVertexAttribPointer(planeTexCoord, 2, GLES20.GL_FLOAT, false, 0, planeTexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(planePosition)
        GLES20.glDisableVertexAttribArray(planeTexCoord)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    
    private fun createWallpaperTexture(): Int {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(224, 216, 202))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(194, 183, 166)
        paint.strokeWidth = 5f
        for (x in 0 until size step 32) {
            canvas.drawRect(x.toFloat(), 0f, (x + 12).toFloat(), size.toFloat(), paint)
        }

        paint.color = Color.rgb(235, 229, 217)
        for (x in 0 until size step 32) {
            canvas.drawRect((x + 12).toFloat(), 0f, (x + 16).toFloat(), size.toFloat(), paint)
        }

        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
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

        private const val PLANE_VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_Mvp * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val PLANE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform vec4 u_Color;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord) * u_Color;
            }
        """
    }
}
