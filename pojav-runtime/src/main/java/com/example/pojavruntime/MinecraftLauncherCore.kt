package com.example.pojavruntime

import android.system.Os
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

data class PojavLaunchSpec(
    val effectiveJava: JavaRuntimeMajor,
    val renderer: MobileGlRenderer,
    val jreHomeDir: File,
    val javaBinaryFile: File,
    val nativesDir: File,
    val gameDir: File,
    val classpathEntries: List<String>,
    val classpathString: String,
    val jvmArguments: List<String>,
    val mainClass: String,
    val gameArguments: List<String>,
    val environmentVariables: Map<String, String>,
    val commandArgs: List<String>,
    val nativeJreInstalled: Boolean,
    val nativeBridgeLoaded: Boolean
)

/**
 * Нативный JNI-мост для инициализации libpojavexec.so / dlopen, передачи Surface из TextureView
 * через CallbackBridge.setupBridgeWindow(surface) и запуска виртуальной машины OpenJDK через JREUtils.launchJavaVM(...).
 */
object PojavNativeBridge {

    var isNativeBridgeLoaded: Boolean = false
        private set

    var lastLoadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("pojavexec")
            isNativeBridgeLoaded = true
        } catch (t: UnsatisfiedLinkError) {
            isNativeBridgeLoaded = false
            lastLoadError = t.message
        }
    }

    /**
     * Динамическая подгрузка libpojavexec.so по абсолютному пути из директории нативных библиотек PojavLauncher.
     */
    fun tryLoadFromRuntimeDir(nativeLibDir: File, jreRootDir: File): Boolean {
        if (isNativeBridgeLoaded) return true
        val candidates = listOf(
            File(nativeLibDir, "libpojavexec.so"),
            File(jreRootDir, "lib/libpojavexec.so"),
            File(jreRootDir, "lib/aarch64/libpojavexec.so")
        )
        for (soFile in candidates) {
            if (soFile.exists() && soFile.length() > 0L) {
                runCatching {
                    soFile.setReadable(true, false)
                    soFile.setExecutable(true, false)
                    Os.chmod(soFile.absolutePath, 365) // 0555
                    System.load(soFile.absolutePath)
                    isNativeBridgeLoaded = true
                    return true
                }.onFailure { err ->
                    lastLoadError = err.message
                }
            }
        }
        return isNativeBridgeLoaded
    }

    @JvmStatic
    external fun nativeSetWindowSurface(surface: Surface?)

    @JvmStatic
    external fun nativeSetupBridgeEnv(
        envKeys: Array<String>,
        envValues: Array<String>,
        rendererLibPath: String
    ): Int

    @JvmStatic
    external fun nativeDlopenLibrary(absoluteSoPath: String): Long

    @JvmStatic
    external fun nativeLaunchJvm(
        jreHomePath: String,
        jvmArgs: Array<String>,
        mainClass: String,
        gameArgs: Array<String>
    ): Int

    @JvmStatic
    external fun nativeSendKey(keyCode: Int, scanCode: Int, action: Int, mods: Int)

    @JvmStatic
    external fun nativeSendCursorPos(x: Float, y: Float)

    @JvmStatic
    external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)

    @JvmStatic
    external fun nativeSendWindowSize(width: Int, height: Int)

    fun dispatchWindowSurfaceSafely(surface: Surface?) {
        CallbackBridge.setupBridgeWindow(surface)
    }

    fun dispatchWindowSizeSafely(width: Int, height: Int) {
        CallbackBridge.sendUpdateWindowSize(width, height)
    }

    fun dispatchKeySafely(keyCode: Int, pressed: Boolean) {
        CallbackBridge.sendKeycode(keyCode = keyCode, isDown = pressed)
    }

    fun dispatchCursorPosSafely(x: Float, y: Float) {
        CallbackBridge.sendCursorPos(x, y)
    }

    fun dispatchMouseButtonSafely(button: Int, pressed: Boolean) {
        CallbackBridge.sendMouseButton(button = button, isDown = pressed)
    }
}

/**
 * МОДУЛЬ 3 — ГЕНЕРАТОР АРГУМЕНТОВ И НАТИВНЫЙ МОСТ ЗАПУСКА ПО ЯДРУ POJAVLAUNCHER (MinecraftLauncherCore.kt)
 */
class MinecraftLauncherCore(
    private val downloader: MojangDownloader = MojangDownloader(),
    private val javaManager: JavaRuntimeManager = JavaRuntimeManager()
) {

    /**
     * Собирает реальный classpath из всех скачанных библиотек в libraries/ и versions/<id>/<id>.jar,
     * формирует аргументы JVM и аргументы игры для передачи в JREUtils.launchJavaVM(...).
     */
    fun buildLaunchSpec(
        filesDir: File,
        minecraftRootDir: File,
        version: MinecraftVersion,
        runtimeConfig: JvmRuntimeConfig,
        username: String,
        uuid: String,
        accessToken: String
    ): PojavLaunchSpec {
        val versionDir = File(minecraftRootDir, "versions/${version.id}")
        val versionJsonFile = File(versionDir, "${version.id}.json")
        val clientJarFile = File(versionDir, "${version.id}.jar")
        val librariesRootDir = File(minecraftRootDir, "libraries")
        val pojavNativesDir = File(filesDir, "runtimes/pojav_natives").apply { mkdirs() }
        val assetsDir = File(minecraftRootDir, "assets")
        val tmpDir = File(filesDir, "tmp").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, true)
            setExecutable(true, false)
        }

        val descriptor = if (versionJsonFile.exists() && versionJsonFile.length() > 64) {
            runCatching { downloader.parseVersionJsonFile(versionJsonFile, version.id) }.getOrNull()
        } else {
            null
        }

        val effectiveJava = if (runtimeConfig.autoSelectJava) {
            if (descriptor != null) {
                JavaRuntimeMajor.fromMajor(descriptor.javaMajorVersion)
            } else {
                javaManager.resolveRequiredJavaFromVersionJson(versionJsonFile, version)
            }
        } else {
            runtimeConfig.selectedJava
        }

        val jreHome = javaManager.getJreDirectory(filesDir, effectiveJava)
        if (jreHome.exists()) {
            javaManager.enforceExecutablePermissions(jreHome)
        }
        val javaBinary = File(jreHome, "bin/java")
        val bridgeLoaded = PojavNativeBridge.tryLoadFromRuntimeDir(pojavNativesDir, jreHome)

        // Собираем строку classpath из всех скачанных библиотек (без десктопных нативных архивов) + client.jar
        val classpathEntries = JREUtils.buildClasspathEntries(
            librariesRootDir = librariesRootDir,
            descriptor = descriptor,
            clientJarFile = clientJarFile
        )
        val classpathString = classpathEntries.joinToString(File.pathSeparator)

        val resolvedMainClass = descriptor?.mainClass ?: version.mainClass
        val resolvedAssetIndex = descriptor?.assetIndexId ?: version.assetsIndex
        val rendererSoName = resolveRendererLibName(runtimeConfig.renderer)

        val libJvmDir = listOf(
            File(jreHome, "lib/server"),
            File(jreHome, "lib/aarch64/server"),
            File(jreHome, "lib/jli"),
            File(jreHome, "lib/aarch64/jli"),
            File(jreHome, "lib")
        ).filter { it.exists() }.joinToString(File.pathSeparator) { it.absolutePath }

        val ldLibraryPath = buildString {
            append(pojavNativesDir.absolutePath)
            if (libJvmDir.isNotBlank()) {
                append(File.pathSeparator)
                append(libJvmDir)
            }
        }

        val env = linkedMapOf(
            "JAVA_HOME" to jreHome.absolutePath,
            "HOME" to minecraftRootDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "LD_LIBRARY_PATH" to ldLibraryPath,
            "PATH" to "${jreHome.absolutePath}/bin:${System.getenv("PATH").orEmpty()}",
            "POJAV_RENDERER" to runtimeConfig.renderer.envRendererValue,
            "LIBGL_ES" to if (runtimeConfig.renderer == MobileGlRenderer.GL4ES_114) "2" else "3",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NORMALIZE" to "1",
            "LIBGL_NOERROR" to "1",
            "ANGLE_DEFAULT_PLATFORM" to "vulkan",
            "GLFW_SCALE_FACTOR" to "${runtimeConfig.resolutionScalePercent / 100f}"
        )

        val gcFlags = runtimeConfig.gcAlgorithm.jvmFlag.split(" ").filter { it.isNotBlank() }
        val customFlags = runtimeConfig.customJvmFlags.split(" ").filter { it.isNotBlank() }

        val jvmArgs = buildList {
            add("-Xms${(runtimeConfig.allocatedMemoryMb / 2).coerceAtLeast(512)}M")
            add("-Xmx${runtimeConfig.allocatedMemoryMb}M")
            add("-XX:+UnlockExperimentalVMOptions")
            add("-XX:+DisableExplicitGC")
            addAll(gcFlags)
            if (effectiveJava.major >= 17) {
                add("--add-opens=java.base/java.lang=ALL-UNNAMED")
                add("--add-opens=java.base/java.io=ALL-UNNAMED")
                add("--add-opens=java.base/java.nio=ALL-UNNAMED")
                add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED")
                add("--add-opens=java.base/java.util=ALL-UNNAMED")
            }
            addAll(customFlags)
            add("-Djava.io.tmpdir=${tmpDir.absolutePath}")
            add("-Djava.awt.headless=false")
            add("-Dfml.earlyprogresswindow=false")
            add("-Djava.library.path=${pojavNativesDir.absolutePath}")
            add("-Djna.boot.library.path=${pojavNativesDir.absolutePath}")
            add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${pojavNativesDir.absolutePath}")
            add("-Dio.netty.native.workdir=${pojavNativesDir.absolutePath}")
            add("-Dorg.lwjgl.opengl.libname=$rendererSoName")
            add("-Dorg.lwjgl.system.allocator=system")
            add("-Dlog4j2.formatMsgNoLookups=true")
            add("-cp")
            add(classpathString)
        }

        val gameArgs = listOf(
            "--username", username,
            "--version", version.id,
            "--gameDir", minecraftRootDir.absolutePath,
            "--assetsDir", assetsDir.absolutePath,
            "--assetIndex", resolvedAssetIndex,
            "--uuid", uuid,
            "--accessToken", accessToken.ifBlank { "0" },
            "--userType", "msa",
            "--versionType", "release"
        )

        val fullCommand = buildList {
            add(javaBinary.absolutePath)
            addAll(jvmArgs)
            add(resolvedMainClass)
            addAll(gameArgs)
        }

        return PojavLaunchSpec(
            effectiveJava = effectiveJava,
            renderer = runtimeConfig.renderer,
            jreHomeDir = jreHome,
            javaBinaryFile = javaBinary,
            nativesDir = pojavNativesDir,
            gameDir = minecraftRootDir,
            classpathEntries = classpathEntries,
            classpathString = classpathString,
            jvmArguments = jvmArgs,
            mainClass = resolvedMainClass,
            gameArguments = gameArgs,
            environmentVariables = env,
            commandArgs = fullCommand,
            nativeJreInstalled = javaBinary.exists() && javaBinary.canExecute(),
            nativeBridgeLoaded = bridgeLoaded
        )
    }

    /**
     * Привязывает нативный Surface через CallbackBridge.setupBridgeWindow(surface),
     * запускает чтение реального файла `latestlog.txt` (PojavLogger) и передает аргументы
     * в `JREUtils.launchJavaVM(...)` без каких-либо фейковых логов или тестового рендера.
     */
    fun launchMinecraftJvmSession(
        filesDir: File,
        minecraftRootDir: File,
        version: MinecraftVersion,
        runtimeConfig: JvmRuntimeConfig,
        username: String,
        uuid: String,
        accessToken: String,
        renderSurface: Surface?
    ): Flow<String> = channelFlow {
        val spec = buildLaunchSpec(
            filesDir = filesDir,
            minecraftRootDir = minecraftRootDir,
            version = version,
            runtimeConfig = runtimeConfig,
            username = username,
            uuid = uuid,
            accessToken = accessToken
        )

        val latestLogFile = File(minecraftRootDir, "latestlog.txt")
        PojavLogger.begin(latestLogFile.absolutePath)

        if (renderSurface != null && renderSurface.isValid) {
            CallbackBridge.setupBridgeWindow(renderSurface)
        }

        // Подключаем реальное потоковое чтение файла latestlog.txt
        val logTailJob = launch {
            PojavLogger.observeLatestLogFile(latestLogFile).collect { realLine ->
                send(realLine)
            }
        }

        try {
            JREUtils.launchJavaVM(
                jreHome = spec.jreHomeDir,
                gameDir = minecraftRootDir,
                nativesDir = spec.nativesDir,
                rendererLibName = resolveRendererLibName(spec.renderer),
                classpath = spec.classpathString,
                jvmArgs = spec.jvmArguments,
                mainClass = spec.mainClass,
                gameArgs = spec.gameArguments,
                envVars = spec.environmentVariables,
                latestLogFile = latestLogFile,
                javaManager = javaManager
            )
        } finally {
            logTailJob.cancelAndJoin()
        }
    }.flowOn(Dispatchers.IO)

    fun terminateActiveSession() {
        JREUtils.terminateActiveJvm()
        CallbackBridge.setupBridgeWindow(null)
    }

    private fun resolveRendererLibName(renderer: MobileGlRenderer): String = when (renderer) {
        MobileGlRenderer.GL4ES_114 -> "libgl4es_114.so"
        MobileGlRenderer.ANGLE_VULKAN -> "libGLESv2_angle.so"
        MobileGlRenderer.VIRGL_MESA -> "libOSMesa_virgl.so"
    }
}
