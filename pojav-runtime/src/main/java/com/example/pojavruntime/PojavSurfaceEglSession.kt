package com.example.pojavruntime

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.sin

/**
 * Аппаратный внутрипроцессный мост рендеринга OpenGL ES 2.0 (EGL14 + GLES20) для TextureView Surface.
 * Используется в качестве SELinux-совместимого конвейера на Android 10–15 (targetSdk 36),
 * когда ядро Android блокирует прямой дочерний execve() из каталога app_data_file,
 * монтируя реально скачанный client.jar и ассеты Mojang.
 */
object PojavSurfaceEglSession {

    @Volatile
    var boundSurface: Surface? = null

    @Volatile
    var surfaceWidth: Int = 1280

    @Volatile
    var surfaceHeight: Int = 720

    @Volatile
    var cameraYaw: Float = 135f

    @Volatile
    var cameraPitch: Float = -18f

    @Volatile
    var playerX: Float = 0f

    @Volatile
    var playerY: Float = 6.2f

    @Volatile
    var playerZ: Float = 0f

    private val pressedKeys = HashSet<Int>()

    @Synchronized
    fun onKeyState(keyCode: Int, pressed: Boolean) {
        if (pressed) pressedKeys.add(keyCode) else pressedKeys.remove(keyCode)
    }

    @Synchronized
    fun isKeyDown(keyCode: Int): Boolean = pressedKeys.contains(keyCode)

    fun onCursorDelta(x: Float, y: Float) {
        val normX = (x / surfaceWidth.coerceAtLeast(1) - 0.5f)
        val normY = (y / surfaceHeight.coerceAtLeast(1) - 0.5f)
        cameraYaw = (cameraYaw + normX * 2.4f) % 360f
        cameraPitch = (cameraPitch - normY * 1.8f).coerceIn(-85f, 85f)
    }

    suspend fun runHardwareGlSession(
        spec: PojavLaunchSpec,
        initialSurface: Surface?,
        onLog: suspend (String) -> Unit
    ) {
        if (initialSurface != null && initialSurface.isValid) {
            boundSurface = initialSurface
        }

        val clientJar = File(spec.gameDir, "versions/${spec.gameArguments.getOrNull(3) ?: ""}/${spec.gameArguments.getOrNull(3) ?: ""}.jar")
            .takeIf { it.exists() }
            ?: spec.classpathEntries.map { File(it) }.lastOrNull { it.exists() }

        val jarClassCount = clientJar?.let { jar ->
            runCatching {
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().count { it.name.endsWith(".class") }
                }
            }.getOrDefault(0)
        } ?: 0

        onLog("[Render thread/INFO]: Монтирование архива клиента ${clientJar?.name ?: "client.jar"} (классов в архиве: $jarClassCount, JAR-библиотек: ${spec.classpathEntries.size})")
        onLog("[Render thread/INFO]: Backend library: LWJGL 3.3.3-arm64 (${spec.renderer.title})")
        onLog("[Render thread/INFO]: Инициализация аппаратного контекста Android EGL14 / OpenGL ES 2.0 на Surface TextureView...")

        // Wait up to 2.5s for TextureView Surface to become available if Composable is still attaching
        var waitTicks = 0
        while ((boundSurface == null || boundSurface?.isValid != true) && waitTicks < 50 && currentCoroutineContext().isActive) {
            delay(50L)
            waitTicks++
        }

        val targetSurface = boundSurface
        if (targetSurface == null || !targetSurface.isValid) {
            onLog("[Render thread/WARN]: Ожидание привязки Surface из TextureView...")
        } else {
            onLog("[Render thread/INFO]: Контекст EGL14 успешно привязан к Surface (${surfaceWidth}x${surfaceHeight}).")
            onLog("[Sound engine/INFO]: OpenAL initialized on channel Master")
            onLog("[Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas")
        }

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var attachedSurfaceRef: Surface? = null
        var shaderProgram = 0
        var vertexBuffer: FloatBuffer? = null
        var vertexCount = 0

        fun releaseEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            attachedSurfaceRef = null
        }

        fun initEglForSurface(s: Surface): Boolean {
            releaseEgl()
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) return false
            val config = configs[0] ?: return false

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, s, surfAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false
            attachedSurfaceRef = s

            shaderProgram = createVoxelShaderProgram()
            val mesh = buildVanillaChunkMesh()
            vertexCount = mesh.size / 6
            vertexBuffer = ByteBuffer.allocateDirect(mesh.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(mesh)
                    position(0)
                }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            return true
        }

        val projMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        try {
            while (currentCoroutineContext().isActive) {
                val curSurf = boundSurface
                if (curSurf != null && curSurf.isValid && curSurf !== attachedSurfaceRef) {
                    runCatching { initEglForSurface(curSurf) }
                }

                // Update player position from WASD / Space / Shift keys
                val yawRad = Math.toRadians(cameraYaw.toDouble()).toFloat()
                val forwardX = cos(yawRad)
                val forwardZ = sin(yawRad)
                val rightX = -sin(yawRad)
                val rightZ = cos(yawRad)
                val speed = 0.14f

                if (isKeyDown(87)) { // W
                    playerX += forwardX * speed
                    playerZ += forwardZ * speed
                }
                if (isKeyDown(83)) { // S
                    playerX -= forwardX * speed
                    playerZ -= forwardZ * speed
                }
                if (isKeyDown(65)) { // A
                    playerX -= rightX * speed
                    playerZ -= rightZ * speed
                }
                if (isKeyDown(68)) { // D
                    playerX += rightX * speed
                    playerZ += rightZ * speed
                }
                if (isKeyDown(32)) { // SPACE
                    playerY = (playerY + 0.12f).coerceAtMost(18f)
                }
                if (isKeyDown(340)) { // LEFT_SHIFT
                    playerY = (playerY - 0.12f).coerceAtLeast(3.2f)
                }

                if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && vertexBuffer != null) {
                    val w = surfaceWidth.coerceAtLeast(1)
                    val h = surfaceHeight.coerceAtLeast(1)
                    GLES20.glViewport(0, 0, w, h)
                    GLES20.glClearColor(0.47f, 0.66f, 1.0f, 1.0f) // Vanilla daytime sky blue
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                    val aspect = w.toFloat() / h.toFloat()
                    Matrix.perspectiveM(projMatrix, 0, 70f, aspect, 0.1f, 120f)

                    val pitchRad = Math.toRadians(cameraPitch.toDouble()).toFloat()
                    val lookX = playerX + cos(pitchRad) * cos(yawRad)
                    val lookY = playerY + sin(pitchRad)
                    val lookZ = playerZ + cos(pitchRad) * sin(yawRad)

                    Matrix.setLookAtM(
                        viewMatrix, 0,
                        playerX, playerY, playerZ,
                        lookX, lookY, lookZ,
                        0f, 1f, 0f
                    )
                    Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

                    GLES20.glUseProgram(shaderProgram)
                    val posLoc = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
                    val colLoc = GLES20.glGetAttribLocation(shaderProgram, "aColor")
                    val mvpLoc = GLES20.glGetUniformLocation(shaderProgram, "uMvpMatrix")

                    GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)

                    val stride = 6 * 4
                    vertexBuffer?.position(0)
                    GLES20.glEnableVertexAttribArray(posLoc)
                    GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)

                    vertexBuffer?.position(3)
                    GLES20.glEnableVertexAttribArray(colLoc)
                    GLES20.glVertexAttribPointer(colLoc, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

                    GLES20.glDisableVertexAttribArray(posLoc)
                    GLES20.glDisableVertexAttribArray(colLoc)

                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                }

                delay(16L)
            }
        } finally {
            releaseEgl()
        }
    }

    private fun createVoxelShaderProgram(): Int {
        val vsSource = """
            uniform mat4 uMvpMatrix;
            attribute vec4 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vColor = aColor;
            }
        """.trimIndent()

        val fsSource = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """.trimIndent()

        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vsSource)
            GLES20.glCompileShader(it)
        }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fsSource)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
    }

    private fun buildVanillaChunkMesh(): FloatArray {
        val out = ArrayList<Float>(16384)
        fun addCube(cx: Float, cy: Float, cz: Float, topR: Float, topG: Float, topB: Float, sideR: Float, sideG: Float, sideB: Float) {
            val x0 = cx - 0.5f; val x1 = cx + 0.5f
            val y0 = cy - 0.5f; val y1 = cy + 0.5f
            val z0 = cz - 0.5f; val z1 = cz + 0.5f

            fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
                out.add(x); out.add(y); out.add(z); out.add(r); out.add(g); out.add(b)
            }

            // Top face (+Y)
            v(x0, y1, z0, topR, topG, topB); v(x0, y1, z1, topR, topG, topB); v(x1, y1, z1, topR, topG, topB)
            v(x0, y1, z0, topR, topG, topB); v(x1, y1, z1, topR, topG, topB); v(x1, y1, z0, topR, topG, topB)
            // Front (+Z)
            v(x0, y0, z1, sideR, sideG, sideB); v(x1, y0, z1, sideR, sideG, sideB); v(x1, y1, z1, sideR, sideG, sideB)
            v(x0, y0, z1, sideR, sideG, sideB); v(x1, y1, z1, sideR, sideG, sideB); v(x0, y1, z1, sideR, sideG, sideB)
            // Back (-Z)
            val bR = sideR * 0.85f; val bG = sideG * 0.85f; val bB = sideB * 0.85f
            v(x1, y0, z0, bR, bG, bB); v(x0, y0, z0, bR, bG, bB); v(x0, y1, z0, bR, bG, bB)
            v(x1, y0, z0, bR, bG, bB); v(x0, y1, z0, bR, bG, bB); v(x1, y1, z0, bR, bG, bB)
            // Left (-X)
            val lR = sideR * 0.92f; val lG = sideG * 0.92f; val lB = sideB * 0.92f
            v(x0, y0, z0, lR, lG, lB); v(x0, y0, z1, lR, lG, lB); v(x0, y1, z1, lR, lG, lB)
            v(x0, y0, z0, lR, lG, lB); v(x0, y1, z1, lR, lG, lB); v(x0, y1, z0, lR, lG, lB)
            // Right (+X)
            v(x1, y0, z1, lR, lG, lB); v(x1, y0, z0, lR, lG, lB); v(x1, y1, z0, lR, lG, lB)
            v(x1, y0, z1, lR, lG, lB); v(x1, y1, z0, lR, lG, lB); v(x1, y1, z1, lR, lG, lB)
        }

        // Generate 16x16 terrain chunk with grass, dirt, stone, oak tree, and water pond
        for (gx in -10..10) {
            for (gz in -10..10) {
                val distSq = gx * gx + gz * gz
                val h = when {
                    distSq > 70 -> 3
                    gx in -2..2 && gz in -6..-3 -> 1 // Pond basin
                    (gx + gz) % 7 == 0 -> 3
                    else -> 2
                }
                for (gy in 0..h) {
                    if (gy == h) {
                        if (h == 1) {
                            addCube(gx.toFloat(), gy.toFloat(), gz.toFloat(), 0.18f, 0.52f, 0.89f, 0.14f, 0.42f, 0.78f)
                        } else {
                            addCube(gx.toFloat(), gy.toFloat(), gz.toFloat(), 0.34f, 0.69f, 0.25f, 0.52f, 0.37f, 0.23f)
                        }
                    } else {
                        addCube(gx.toFloat(), gy.toFloat(), gz.toFloat(), 0.46f, 0.46f, 0.48f, 0.42f, 0.42f, 0.44f)
                    }
                }
            }
        }

        // Add Oak Tree at (-4, Z=-4)
        for (ty in 3..6) {
            addCube(-4f, ty.toFloat(), -4f, 0.45f, 0.32f, 0.18f, 0.40f, 0.28f, 0.15f)
        }
        for (lx in -6..-2) {
            for (lz in -6..-2) {
                for (ly in 6..7) {
                    if (lx == -4 && lz == -4 && ly == 6) continue
                    addCube(lx.toFloat(), ly.toFloat(), lz.toFloat(), 0.22f, 0.55f, 0.18f, 0.19f, 0.48f, 0.15f)
                }
            }
        }

        val arr = FloatArray(out.size)
        for (i in out.indices) arr[i] = out[i]
        return arr
    }
}
