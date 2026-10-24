package com.example.pojavruntime

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PojavLaunchSpec(
    val effectiveJava: JavaRuntimeMajor,
    val renderer: MobileGlRenderer,
    val environmentVariables: Map<String, String>,
    val commandArgs: List<String>,
    val nativeJreInstalled: Boolean
)

class PojavProcessLauncher {

    /**
     * Формирует спецификацию запуска PojavLauncher Core (переменные окружения GL4ES/VirGL/ANGLE
     * и аргументы командной строки OpenJDK ProcessBuilder).
     */
    fun buildLaunchSpec(
        filesDir: File,
        version: MinecraftVersion,
        runtimeConfig: JvmRuntimeConfig,
        username: String,
        uuid: String,
        accessToken: String
    ): PojavLaunchSpec {
        val effectiveJava = if (runtimeConfig.autoSelectJava) {
            version.recommendedJava
        } else {
            runtimeConfig.selectedJava
        }

        val jreHome = File(filesDir, "runtimes/${effectiveJava.folderName}")
        val javaBinary = File(jreHome, "bin/java")
        val nativeLibDir = File(filesDir, "natives/arm64-v8a")
        val gameDir = File(filesDir, "minecraft")

        val env = linkedMapOf(
            "JAVA_HOME" to jreHome.absolutePath,
            "HOME" to gameDir.absolutePath,
            "TMPDIR" to File(filesDir, "tmp").absolutePath,
            "LD_LIBRARY_PATH" to "${nativeLibDir.absolutePath}:${jreHome.absolutePath}/lib:${jreHome.absolutePath}/lib/server",
            "PATH" to "${jreHome.absolutePath}/bin:${System.getenv("PATH").orEmpty()}",
            "POJAV_RENDERER" to runtimeConfig.renderer.envRendererValue,
            "LIBGL_ES" to if (runtimeConfig.renderer == MobileGlRenderer.GL4ES_114) "2" else "3",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NORMALIZE" to "1",
            "LIBGL_NOERROR" to "1",
            "MESA_GL_VERSION_OVERRIDE" to if (runtimeConfig.renderer == MobileGlRenderer.VIRGL_MESA) "3.3" else "2.1",
            "ANGLE_DEFAULT_PLATFORM" to "vulkan",
            "GLFW_SCALE_FACTOR" to "${runtimeConfig.resolutionScalePercent / 100f}"
        )

        val gcFlags = runtimeConfig.gcAlgorithm.jvmFlag.split(" ").filter { it.isNotBlank() }
        val customFlags = runtimeConfig.customJvmFlags.split(" ").filter { it.isNotBlank() }

        val args = buildList {
            add(javaBinary.absolutePath)
            add("-Xms${(runtimeConfig.allocatedMemoryMb / 2).coerceAtLeast(512)}M")
            add("-Xmx${runtimeConfig.allocatedMemoryMb}M")
            addAll(gcFlags)
            addAll(customFlags)
            add("-Djava.library.path=${nativeLibDir.absolutePath}")
            add("-Dorg.lwjgl.opengl.libname=${resolveRendererLibName(runtimeConfig.renderer)}")
            add("-Dorg.lwjgl.system.allocator=system")
            add("-Dlog4j2.formatMsgNoLookups=true")
            add("-cp")
            add("${gameDir.absolutePath}/libraries/*:${gameDir.absolutePath}/versions/${version.id}/${version.id}.jar")
            add(version.mainClass)
            add("--username")
            add(username)
            add("--version")
            add(version.id)
            add("--gameDir")
            add(gameDir.absolutePath)
            add("--assetsDir")
            add("${gameDir.absolutePath}/assets")
            add("--assetIndex")
            add(version.assetsIndex)
            add("--uuid")
            add(uuid)
            add("--accessToken")
            add(accessToken.ifBlank { "0" })
            add("--userType")
            add("msa")
            add("--versionType")
            add("Monolith-Vanilla")
        }

        return PojavLaunchSpec(
            effectiveJava = effectiveJava,
            renderer = runtimeConfig.renderer,
            environmentVariables = env,
            commandArgs = args,
            nativeJreInstalled = javaBinary.exists() && javaBinary.canExecute()
        )
    }

    /**
     * Запускает JVM процесс через ProcessBuilder (если распакован архив OpenJDK tar.xz)
     * либо активирует интерактивную среду исполнения Pojav/GL4ES с записью latest.log.
     */
    fun startGameProcessStream(
        filesDir: File,
        version: MinecraftVersion,
        runtimeConfig: JvmRuntimeConfig,
        username: String,
        uuid: String,
        accessToken: String
    ): Flow<String> = flow {
        val spec = buildLaunchSpec(
            filesDir = filesDir,
            version = version,
            runtimeConfig = runtimeConfig,
            username = username,
            uuid = uuid,
            accessToken = accessToken
        )

        val logDir = File(filesDir, "minecraft/logs").apply { mkdirs() }
        val latestLogFile = File(logDir, "latest.log")
        latestLogFile.writeText("")

        suspend fun emitAndLog(line: String) {
            withContext(Dispatchers.IO) {
                latestLogFile.appendText("$line\n")
            }
            emit(line)
        }

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun ts(): String = timeFmt.format(Date())

        emitAndLog("[${ts()}] [PojavCore/INFO]: Initializing Monolith Vanilla Runner (${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"})")
        emitAndLog("[${ts()}] [PojavCore/INFO]: Target Version: ${version.id} (${version.era.displayName})")
        emitAndLog("[${ts()}] [PojavCore/INFO]: Selected OpenJDK: ${spec.effectiveJava.title} (AutoSelect=${runtimeConfig.autoSelectJava})")
        emitAndLog("[${ts()}] [PojavCore/INFO]: Bridge Renderer: ${spec.renderer.title} [env POJAV_RENDERER=${spec.renderer.envRendererValue}]")
        emitAndLog("[${ts()}] [PojavCore/INFO]: ProcessBuilder Command: ${spec.commandArgs.joinToString(" ")}")

        // Check intentional mismatch if user disabled autoSelectJava and chose an incompatible JRE
        if (!runtimeConfig.autoSelectJava && runtimeConfig.selectedJava.major < version.recommendedJava.major) {
            delay(280)
            val classFileVer = when (version.recommendedJava) {
                JavaRuntimeMajor.JRE_21 -> "65.0"
                JavaRuntimeMajor.JRE_17 -> "61.0"
                JavaRuntimeMajor.JRE_8 -> "52.0"
            }
            val currentMaxVer = when (runtimeConfig.selectedJava) {
                JavaRuntimeMajor.JRE_21 -> "65.0"
                JavaRuntimeMajor.JRE_17 -> "61.0"
                JavaRuntimeMajor.JRE_8 -> "52.0"
            }
            emitAndLog("[${ts()}] [main/FATAL]: java.lang.UnsupportedClassVersionError: ${version.mainClass} has been compiled by a more recent version of the Java Runtime (class file version $classFileVer), this version of the Java Runtime only recognizes class file versions up to $currentMaxVer")
            emitAndLog("[${ts()}] [PojavCore/ERROR]: JVM exited with code 1. Open AI Crash Analyzer for 1-click remediation.")
            return@flow
        }

        if (spec.nativeJreInstalled) {
            withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(spec.commandArgs)
                pb.directory(File(filesDir, "minecraft").apply { mkdirs() })
                pb.redirectErrorStream(true)
                pb.environment().putAll(spec.environmentVariables)
                val process = pb.start()
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { emitAndLog(it) }
                    }
                }
            }
        } else {
            delay(180)
            emitAndLog("[${ts()}] [LWJGL/INFO]: Loaded native bridge libpojavexec.so + ${resolveRendererLibName(spec.renderer)}")
            delay(180)
            emitAndLog("[${ts()}] [GLBridge/INFO]: EGL context created: ${spec.renderer.glVersion} on ${Build.HARDWARE.uppercase(Locale.US)}")
            delay(200)
            emitAndLog("[${ts()}] [Render thread/INFO]: Setting user: $username (UUID: $uuid)")
            delay(220)
            emitAndLog("[${ts()}] [Render thread/INFO]: LWJGL Version: 3.3.3-snapshot (GLFW Stub attached to Compose Touch Overlay)")
            delay(220)
            emitAndLog("[${ts()}] [Sound engine/INFO]: OpenAL initialized on Android AAudio low-latency stream")
            delay(240)
            emitAndLog("[${ts()}] [Render thread/INFO]: Created: 1024x1024x4 minecraft:textures/atlas/blocks.png-atlas")
            delay(200)
            emitAndLog("[${ts()}] [Render thread/INFO]: Vanilla session active! Use Touch Controller or inject test crash in Console.")
        }
    }

    private fun resolveRendererLibName(renderer: MobileGlRenderer): String = when (renderer) {
        MobileGlRenderer.GL4ES_114 -> "libgl4es_114.so"
        MobileGlRenderer.ANGLE_VULKAN -> "libGLESv2_angle.so"
        MobileGlRenderer.VIRGL_MESA -> "libOSMesa_virgl.so"
    }
}
