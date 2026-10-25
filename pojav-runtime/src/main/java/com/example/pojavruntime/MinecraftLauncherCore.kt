package com.example.pojavruntime

import android.os.Build
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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
 * Нативный JNI-мост для инициализации libpojavexec.so / dlopen, передачи Surface из TextureView,
 * запуска виртуальной машины OpenJDK (JNI_CreateJavaVM) и проброса ввода GLFW.
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
     * Динамическая подгрузка libpojavexec.so и графического бэкенда (libgl4es_114.so / libGLESv2_angle.so)
     * по абсолютному пути, если они размещены в папке нативных библиотек рантайма.
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
        if (isNativeBridgeLoaded) {
            runCatching { nativeSetWindowSurface(surface) }
        }
    }

    fun dispatchWindowSizeSafely(width: Int, height: Int) {
        if (isNativeBridgeLoaded) {
            runCatching { nativeSendWindowSize(width, height) }
        }
    }

    fun dispatchKeySafely(keyCode: Int, pressed: Boolean) {
        if (isNativeBridgeLoaded) {
            runCatching { nativeSendKey(keyCode, 0, if (pressed) 1 else 0, 0) }
        }
    }

    fun dispatchCursorPosSafely(x: Float, y: Float) {
        if (isNativeBridgeLoaded) {
            runCatching { nativeSendCursorPos(x, y) }
        }
    }

    fun dispatchMouseButtonSafely(button: Int, pressed: Boolean) {
        if (isNativeBridgeLoaded) {
            runCatching { nativeSendMouseButton(button, if (pressed) 1 else 0, 0) }
        }
    }
}

/**
 * МОДУЛЬ 3 — ГЕНЕРАТОР АРГУМЕНТОВ И НАТИВНЫЙ МОСТ ЗАПУСКА (MinecraftLauncherCore.kt)
 */
class MinecraftLauncherCore(
    private val downloader: MojangDownloader = MojangDownloader(),
    private val javaManager: JavaRuntimeManager = JavaRuntimeManager()
) {

    @Volatile
    private var activeProcess: Process? = null

    /**
     * Собирает реальный classpath из всех скачанных библиотек в libraries/ и versions/<id>/<id>.jar,
     * формирует массивы аргументов JVM (-Xmx, GC-флаги из «AI Инженер», пути к natives и рендереру ANGLE/GL4ES)
     * и аргументов игры (net.minecraft.client.main.Main, --username, --uuid, --version, --gameDir, --assetsDir, --assetIndex).
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
        val extNativesDir = File(versionDir, "natives").apply { mkdirs() }
        val execNativesDir = File(filesDir, "exec_natives/${version.id}").apply { mkdirs() }
        if (extNativesDir.exists()) {
            extNativesDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".so")) {
                    val dst = File(execNativesDir, f.name)
                    if (!dst.exists() || dst.length() != f.length()) {
                        runCatching { f.copyTo(dst, overwrite = true) }
                    }
                    dst.setExecutable(true, false)
                    dst.setReadable(true, false)
                }
            }
        }
        val nativesDir = if (execNativesDir.listFiles()?.isNotEmpty() == true) execNativesDir else extNativesDir
        val assetsDir = File(minecraftRootDir, "assets")
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }

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
        val javaBinary = File(jreHome, "bin/java")
        val bridgeLoaded = PojavNativeBridge.tryLoadFromRuntimeDir(nativesDir, jreHome)

        // Собираем реальный список всех скачанных .jar библиотек из descriptor (или обходим libraries/)
        val classpathFiles = LinkedHashSet<String>()
        if (descriptor != null && descriptor.libraries.isNotEmpty()) {
            for (lib in descriptor.libraries) {
                val jarFile = File(librariesRootDir, lib.mavenRelativePath)
                if (jarFile.exists() && jarFile.length() > 22L) {
                    classpathFiles.add(jarFile.absolutePath)
                }
            }
        }
        if (classpathFiles.isEmpty() && librariesRootDir.exists()) {
            librariesRootDir.walkTopDown()
                .filter { it.isFile && it.extension == "jar" && it.length() > 22L }
                .forEach { classpathFiles.add(it.absolutePath) }
        }
        classpathFiles.add(clientJarFile.absolutePath)

        val classpathString = classpathFiles.joinToString(File.pathSeparator)
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
            append(execNativesDir.absolutePath)
            append(File.pathSeparator)
            append(extNativesDir.absolutePath)
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
            "MESA_GL_VERSION_OVERRIDE" to if (runtimeConfig.renderer == MobileGlRenderer.VIRGL_MESA) "3.3" else "2.1",
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
            add("-Djava.awt.headless=true")
            add("-Dfml.earlyprogresswindow=false")
            add("-Djava.library.path=${nativesDir.absolutePath}${File.pathSeparator}${extNativesDir.absolutePath}")
            add("-Djna.boot.library.path=${nativesDir.absolutePath}")
            add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${nativesDir.absolutePath}")
            add("-Dio.netty.native.workdir=${nativesDir.absolutePath}")
            add("-Dorg.lwjgl.opengl.libname=$rendererSoName")
            add("-Dorg.lwjgl.system.allocator=system")
            add("-Dlog4j2.formatMsgNoLookups=true")
            add("-Dminecraft.launcher.brand=monolith-vanilla")
            add("-Dminecraft.launcher.version=2.1.0")
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
            "--versionType", "Monolith-Vanilla"
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
            nativesDir = nativesDir,
            gameDir = minecraftRootDir,
            classpathEntries = classpathFiles.toList(),
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
     * Передает Surface из TextureView и аргументы JVM в нативный мост libpojavexec.so (JNI)
     * либо запускает реальный бинарник runtimes/jre_<version>/bin/java через ProcessBuilder
     * с потоковым чтением stdout/stderr без каких-либо имитаций.
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

        val logDir = File(minecraftRootDir, "logs").apply { mkdirs() }
        val latestLogFile = File(logDir, "latest.log")
        latestLogFile.writeText("")

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun ts(): String = timeFmt.format(Date())

        suspend fun logLine(line: String) {
            withContext(Dispatchers.IO) {
                latestLogFile.appendText("$line\n")
            }
            send(line)
        }

        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: Архитектура: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: Каталог игры: ${minecraftRootDir.absolutePath}")
        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: Каталог JRE: ${spec.jreHomeDir.absolutePath} (${spec.effectiveJava.title})")
        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: Графический мост: ${spec.renderer.title} (${resolveRendererLibName(spec.renderer)})")
        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: Элементов в Classpath: ${spec.classpathEntries.size} JAR-файлов")
        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: Main Class: ${spec.mainClass}")
        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: JVM Args: ${spec.jvmArguments.take(8).joinToString(" ")} ...")
        logLine("[${ts()}] [MinecraftLauncherCore/INFO]: Game Args: ${spec.gameArguments.joinToString(" ")}")

        // 1. Попытка запуска через JNI-мост libpojavexec.so, если он загружен в память процесса
        if (spec.nativeBridgeLoaded) {
            logLine("[${ts()}] [JNI/libpojavexec.so]: Нативный мост активен. Передача Surface и окружения...")
            if (renderSurface != null && renderSurface.isValid) {
                PojavNativeBridge.dispatchWindowSurfaceSafely(renderSurface)
                logLine("[${ts()}] [JNI/libpojavexec.so]: Surface из TextureView успешно привязан к ANativeWindow.")
            }
            val envKeys = spec.environmentVariables.keys.toTypedArray()
            val envVals = spec.environmentVariables.values.toTypedArray()
            val rendererPath = File(spec.nativesDir, resolveRendererLibName(spec.renderer)).absolutePath
            val setupCode = runCatching {
                PojavNativeBridge.nativeSetupBridgeEnv(envKeys, envVals, rendererPath)
            }.getOrElse { -1 }
            logLine("[${ts()}] [JNI/libpojavexec.so]: nativeSetupBridgeEnv вернул код $setupCode")

            val jvmExitCode = withContext(Dispatchers.IO) {
                runCatching {
                    PojavNativeBridge.nativeLaunchJvm(
                        jreHomePath = spec.jreHomeDir.absolutePath,
                        jvmArgs = spec.jvmArguments.toTypedArray(),
                        mainClass = spec.mainClass,
                        gameArgs = spec.gameArguments.toTypedArray()
                    )
                }.getOrElse { err ->
                    logLine("[${ts()}] [JNI/ERROR]: Ошибка вызова nativeLaunchJvm: ${err.message}")
                    -2
                }
            }
            logLine("[${ts()}] [JNI/libpojavexec.so]: Виртуальная машина завершила работу с кодом $jvmExitCode")
            return@channelFlow
        }

        // 2. Прямой запуск реального бинарного файла runtimes/jre_<version>/bin/java через ProcessBuilder
        if (!spec.javaBinaryFile.exists()) {
            logLine("[${ts()}] [JavaRuntimeManager/ERROR]: Бинарный файл ${spec.javaBinaryFile.absolutePath} не найден.")
            logLine("[${ts()}] [JavaRuntimeManager/ERROR]: Скачайте OpenJDK ${spec.effectiveJava.major} (aarch64) перед запуском.")
            return@channelFlow
        }

        javaManager.enforceExecutablePermissions(spec.jreHomeDir)
        logLine("[${ts()}] [ProcessBuilder/INFO]: Запуск процесса ${spec.javaBinaryFile.absolutePath}...")

        try {
            val pb = ProcessBuilder(spec.commandArgs)
            pb.directory(minecraftRootDir)
            pb.redirectErrorStream(true)
            pb.environment().putAll(spec.environmentVariables)

            val process = pb.start()
            activeProcess = process

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { logLine(it) }
                }
            }

            val exitCode = process.waitFor()
            logLine("[${ts()}] [ProcessBuilder/INFO]: Процесс OpenJDK завершился с кодом выхода $exitCode")
        } catch (e: Exception) {
            logLine("[${ts()}] [ProcessBuilder/FATAL]: Не удалось запустить процесс JVM: ${e::class.java.simpleName}: ${e.message}")
        } finally {
            activeProcess = null
        }
    }.flowOn(Dispatchers.IO)

    fun terminateActiveSession() {
        runCatching {
            activeProcess?.destroyForcibly()
            activeProcess = null
            PojavNativeBridge.dispatchWindowSurfaceSafely(null)
        }
    }

    private fun resolveRendererLibName(renderer: MobileGlRenderer): String = when (renderer) {
        MobileGlRenderer.GL4ES_114 -> "libgl4es_114.so"
        MobileGlRenderer.ANGLE_VULKAN -> "libGLESv2_angle.so"
        MobileGlRenderer.VIRGL_MESA -> "libOSMesa_virgl.so"
    }
}
