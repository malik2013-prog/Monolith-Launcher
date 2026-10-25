package com.example.pojavruntime

import android.system.Os
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Мост окна и ввода GLFW ядра PojavLauncher (`org.lwjgl.glfw.CallbackBridge`).
 * Принимает нативный Surface из TextureView.SurfaceTextureListener.onSurfaceTextureAvailable
 * через метод CallbackBridge.setupBridgeWindow(surface) и передает события клавиатуры/мыши в JNI.
 */
object CallbackBridge {

    @Volatile
    var windowWidth: Int = 1280

    @Volatile
    var windowHeight: Int = 720

    @Volatile
    var physicalWidth: Int = 1280

    @Volatile
    var physicalHeight: Int = 720

    @Volatile
    var mouseX: Float = 0f

    @Volatile
    var mouseY: Float = 0f

    @Volatile
    private var activeSurface: Surface? = null

    /**
     * Передает нативный Surface из TextureView в JNI-метод ядра PojavLauncher.
     */
    @JvmStatic
    fun setupBridgeWindow(surface: Surface?) {
        activeSurface = surface
        if (PojavNativeBridge.isNativeBridgeLoaded) {
            runCatching { nativeSetupBridgeWindow(surface) }
                .onFailure {
                    runCatching { PojavNativeBridge.nativeSetWindowSurface(surface) }
                }
        }
    }

    @JvmStatic
    fun sendUpdateWindowSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        windowWidth = width
        windowHeight = height
        physicalWidth = width
        physicalHeight = height
        if (PojavNativeBridge.isNativeBridgeLoaded) {
            runCatching { nativeSendScreenSize(width, height) }
                .onFailure {
                    runCatching { PojavNativeBridge.nativeSendWindowSize(width, height) }
                }
        }
    }

    @JvmStatic
    fun sendKeycode(
        keyCode: Int,
        keyChar: Char = '\u0000',
        scancode: Int = 0,
        modifiers: Int = 0,
        isDown: Boolean
    ) {
        if (PojavNativeBridge.isNativeBridgeLoaded) {
            runCatching {
                nativeSendKey(keyCode, scancode, if (isDown) 1 else 0, modifiers)
            }.onFailure {
                runCatching { PojavNativeBridge.nativeSendKey(keyCode, scancode, if (isDown) 1 else 0, modifiers) }
            }
        }
    }

    @JvmStatic
    fun sendCursorPos(x: Float, y: Float) {
        mouseX = x
        mouseY = y
        if (PojavNativeBridge.isNativeBridgeLoaded) {
            runCatching { nativeSendCursorPos(x, y) }
                .onFailure {
                    runCatching { PojavNativeBridge.nativeSendCursorPos(x, y) }
                }
        }
    }

    @JvmStatic
    fun sendMouseButton(button: Int, isDown: Boolean, modifiers: Int = 0) {
        if (PojavNativeBridge.isNativeBridgeLoaded) {
            runCatching {
                nativeSendMouseButton(button, if (isDown) 1 else 0, modifiers)
            }.onFailure {
                runCatching { PojavNativeBridge.nativeSendMouseButton(button, if (isDown) 1 else 0, modifiers) }
            }
        }
    }

    @JvmStatic
    external fun nativeSetupBridgeWindow(surface: Any?): Boolean

    @JvmStatic
    external fun nativeSendScreenSize(width: Int, height: Int)

    @JvmStatic
    external fun nativeSendKey(keyCode: Int, scanCode: Int, action: Int, mods: Int)

    @JvmStatic
    external fun nativeSendCursorPos(x: Float, y: Float)

    @JvmStatic
    external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)
}

/**
 * Потоковый логгер ядра PojavLauncher (`net.kdt.pojavlaunch.Logger`) и прямое чтение файла `latestlog.txt`.
 * Не содержит никаких захардкоженных или симулированных строк — выводит исключительно реальные данные
 * из `latestlog.txt` и колбэков JNI логгера.
 */
object PojavLogger {

    fun interface EventLogListener {
        fun onEventLogged(text: String)
    }

    private val listeners = CopyOnWriteArrayList<EventLogListener>()

    fun addLogListener(listener: EventLogListener) {
        listeners.add(listener)
    }

    fun removeLogListener(listener: EventLogListener) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun begin(logFilePath: String) {
        val logFile = File(logFilePath)
        logFile.parentFile?.mkdirs()
        FileOutputStream(logFile, false).close()
        if (PojavNativeBridge.isNativeBridgeLoaded) {
            runCatching { nativeBeginLog(logFilePath) }
        }
    }

    @JvmStatic
    fun appendToLog(logFile: File, text: String) {
        runCatching {
            logFile.parentFile?.mkdirs()
            FileOutputStream(logFile, true).use { fos ->
                fos.write((text + "\n").toByteArray(StandardCharsets.UTF_8))
                fos.flush()
            }
        }
        for (l in listeners) {
            l.onEventLogged(text)
        }
    }

    /**
     * Подключается к реальному файлу `latestlog.txt` (и `logs/latest.log`) и в реальном времени
     * вычитывает новые строки по мере их записи ядром PojavLauncher / процессом JVM.
     */
    fun observeLatestLogFile(latestLogFile: File): Flow<String> = channelFlow {
        latestLogFile.parentFile?.mkdirs()
        if (!latestLogFile.exists()) {
            withContext(Dispatchers.IO) {
                latestLogFile.createNewFile()
            }
        }

        val jniListener = EventLogListener { line ->
            if (line.isNotBlank()) {
                trySend(line)
            }
        }
        addLogListener(jniListener)

        try {
            var filePointer = 0L
            while (currentCoroutineContext().isActive) {
                if (latestLogFile.exists()) {
                    val currentLength = latestLogFile.length()
                    if (currentLength < filePointer) {
                        // Файл latestlog.txt был перезаписан при новом запуске
                        filePointer = 0L
                    }
                    if (currentLength > filePointer) {
                        withContext(Dispatchers.IO) {
                            RandomAccessFile(latestLogFile, "r").use { raf ->
                                raf.seek(filePointer)
                                val bytesToRead = (currentLength - filePointer).toInt()
                                val buffer = ByteArray(bytesToRead)
                                val readCount = raf.read(buffer)
                                if (readCount > 0) {
                                    filePointer += readCount
                                    val chunkText = String(buffer, 0, readCount, StandardCharsets.UTF_8)
                                    chunkText.lineSequence()
                                        .map { it.trimEnd('\r') }
                                        .filter { it.isNotEmpty() }
                                        .forEach { line -> trySend(line) }
                                }
                            }
                        }
                    }
                }
                delay(120L)
            }
        } finally {
            removeLogListener(jniListener)
        }
    }.flowOn(Dispatchers.IO)

    @JvmStatic
    external fun nativeBeginLog(logFilePath: String)
}

/**
 * Утилита инициализации окружения и запуска виртуальной машины Java ядра PojavLauncher (`JREUtils`).
 * Формирует строку `classpath` из всех скачанных библиотек + `client.jar`, выставляет переменные окружения
 * через `Os.setenv` и вызывает `JREUtils.launchJavaVM(...)`.
 */
object JREUtils {

    @Volatile
    private var currentJvmProcess: Process? = null

    /**
     * Собирает полную строку classpath (`-cp`) из всех скачанных библиотек в `libraries/` (без десктопных нативных архивов)
     * и основного `versions/<id>/<id>.jar`.
     */
    fun buildClasspathEntries(
        librariesRootDir: File,
        descriptor: ParsedVersionDescriptor?,
        clientJarFile: File
    ): List<String> {
        val entries = LinkedHashSet<String>()
        if (descriptor != null && descriptor.libraries.isNotEmpty()) {
            for (lib in descriptor.libraries) {
                if (lib.isNativeLibrary) continue
                val jarFile = File(librariesRootDir, lib.mavenRelativePath)
                if (jarFile.exists() && jarFile.isFile && jarFile.length() > 0L) {
                    entries.add(jarFile.absolutePath)
                }
            }
        }
        if (entries.isEmpty() && librariesRootDir.exists()) {
            librariesRootDir.walkTopDown()
                .filter {
                    it.isFile &&
                        it.extension.equals("jar", ignoreCase = true) &&
                        !it.name.contains("-natives-", ignoreCase = true) &&
                        it.length() > 0L
                }
                .forEach { entries.add(it.absolutePath) }
        }
        entries.add(clientJarFile.absolutePath)
        return entries.toList()
    }

    /**
     * Программно устанавливает переменные окружения (`JAVA_HOME`, `LD_LIBRARY_PATH`, `POJAV_RENDERER` и др.)
     * в текущем процессе через `Os.setenv` перед вызовом JNI `dlopen` и `JNI_CreateJavaVM`.
     */
    fun setJavaEnvironment(envVariables: Map<String, String>) {
        for ((key, value) in envVariables) {
            runCatching { Os.setenv(key, value, true) }
        }
    }

    /**
     * Запускает виртуальную машину Java через JNI ядра PojavLauncher (`executeJVM` / `nativeLaunchJvm`)
     * либо через прямой бинарный вызов `bin/java` с перенаправлением всего `stdout`/`stderr` в `latestlog.txt`.
     */
    suspend fun launchJavaVM(
        jreHome: File,
        gameDir: File,
        nativesDir: File,
        rendererLibName: String,
        classpath: String,
        jvmArgs: List<String>,
        mainClass: String,
        gameArgs: List<String>,
        envVars: Map<String, String>,
        latestLogFile: File,
        javaManager: JavaRuntimeManager
    ): Int = withContext(Dispatchers.IO) {
        javaManager.enforceExecutablePermissions(jreHome)
        setJavaEnvironment(envVars)

        // Собираем полный вектор аргументов инициализации JVM, включая -cp <classpath>, MainClass и аргументы игры
        val fullJvmInvocation = buildList {
            addAll(jvmArgs)
            if (!jvmArgs.contains("-cp") && !jvmArgs.contains("-classpath")) {
                add("-cp")
                add(classpath)
            }
            add(mainClass)
            addAll(gameArgs)
        }

        // 1. Если загружено нативное ядро PojavLauncher (libpojavexec.so), вызываем JNI launchJavaVM
        if (PojavNativeBridge.isNativeBridgeLoaded) {
            runCatching { chdir(gameDir.absolutePath) }
            val envKeys = envVars.keys.toTypedArray()
            val envValues = envVars.values.toTypedArray()
            val rendererPath = File(nativesDir, rendererLibName).absolutePath
            runCatching {
                PojavNativeBridge.nativeSetupBridgeEnv(envKeys, envValues, rendererPath)
            }

            return@withContext runCatching {
                executeJVM(fullJvmInvocation.toTypedArray())
            }.getOrElse {
                runCatching {
                    PojavNativeBridge.nativeLaunchJvm(
                        jreHomePath = jreHome.absolutePath,
                        jvmArgs = jvmArgs.toTypedArray(),
                        mainClass = mainClass,
                        gameArgs = gameArgs.toTypedArray()
                    )
                }.getOrElse { err ->
                    PojavLogger.appendToLog(latestLogFile, "${err::class.java.name}: ${err.message}")
                    -1
                }
            }
        }

        // 2. Прямой запуск бинарного файла JRE с записью реального потока вывода в latestlog.txt
        val javaBinary = File(jreHome, "bin/java")
        if (!javaBinary.exists()) {
            return@withContext -2
        }

        val commandLine = buildList {
            add(javaBinary.absolutePath)
            addAll(fullJvmInvocation)
        }

        val pb = ProcessBuilder(commandLine)
            .directory(gameDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(latestLogFile))
        pb.environment().putAll(envVars)

        val process = pb.start()
        currentJvmProcess = process
        try {
            process.waitFor()
        } finally {
            currentJvmProcess = null
        }
    }

    fun terminateActiveJvm() {
        runCatching {
            currentJvmProcess?.destroyForcibly()
            currentJvmProcess = null
        }
    }

    @JvmStatic
    external fun chdir(path: String): Int

    @JvmStatic
    external fun executeJVM(args: Array<String>): Int
}
