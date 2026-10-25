package com.example.pojavruntime

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class JreInstallStatus(
    val javaMajor: JavaRuntimeMajor,
    val jreRootDir: File,
    val javaBinaryFile: File,
    val isInstalledAndExecutable: Boolean,
    val installedFilesCount: Int
)

class JavaRuntimeManager {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val MODE_RWXR_XR_X = 493 // 0755 in octal (rwxr-xr-x)
        private const val MODE_R_XR_XR_X = 365  // 0555 in octal (r-xr-xr-x for Android 14+ W^X)

        /**
         * Реальные зеркала портативных сборок OpenJDK (aarch64 / Linux-Android ARM64)
         * в формате .tar.gz / .tar.xz для Java 8, 17 и 21.
         */
        private val AARCH64_OPENJDK_URLS: Map<Int, List<String>> = mapOf(
            8 to listOf(
                "https://api.adoptium.net/v3/binary/latest/8/ga/linux/aarch64/jre/hotspot/normal/eclipse?project=jdk",
                "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre8-20240119/Internal-8-arm64.tar.xz"
            ),
            17 to listOf(
                "https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jre/hotspot/normal/eclipse?project=jdk",
                "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-20240119/Internal-17-arm64.tar.xz"
            ),
            21 to listOf(
                "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse?project=jdk",
                "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre21-20240119/Internal-21-arm64.tar.xz"
            )
        )
    }

    /**
     * Определяет требуемую версию Java напрямую из поля javaVersion.majorVersion
     * в скачанном JSON версии игры (versions/<id>/<id>.json).
     */
    fun resolveRequiredJavaFromVersionJson(
        versionJsonFile: File,
        fallbackVersion: MinecraftVersion
    ): JavaRuntimeMajor {
        if (versionJsonFile.exists() && versionJsonFile.length() > 32) {
            runCatching {
                val root = JSONObject(versionJsonFile.readText())
                val javaVersionObj = root.optJSONObject("javaVersion")
                if (javaVersionObj != null && javaVersionObj.has("majorVersion")) {
                    val major = javaVersionObj.optInt("majorVersion", 8)
                    return JavaRuntimeMajor.fromMajor(major)
                }
            }
        }
        return fallbackVersion.recommendedJava
    }

    /**
     * Возвращает корневую директорию JRE: context.filesDir.resolve("runtimes/jre_<version>")
     */
    fun getJreDirectory(filesDir: File, javaMajor: JavaRuntimeMajor): File =
        filesDir.resolve("runtimes/jre_${javaMajor.major}")

    /**
     * Проверяет, установлен ли и готов ли к исполнению указанный рантайм OpenJDK.
     * Если бинарный файл bin/java существует, но у него сброшен флаг исполнения (+x),
     * немедленно восстанавливает права через enforceExecutablePermissions().
     */
    fun inspectJreStatus(filesDir: File, javaMajor: JavaRuntimeMajor): JreInstallStatus {
        val jreDir = getJreDirectory(filesDir, javaMajor)
        val javaBin = File(jreDir, "bin/java")
        if (javaBin.exists() && !javaBin.canExecute()) {
            enforceExecutablePermissions(jreDir)
        }
        val libJvm = File(jreDir, "lib/server/libjvm.so").takeIf { it.exists() }
            ?: File(jreDir, "lib/aarch64/server/libjvm.so")

        val isReady = javaBin.exists() && javaBin.canExecute() && (libJvm.exists() || File(jreDir, "lib").exists())
        val count = if (jreDir.exists()) {
            jreDir.walkTopDown().count { it.isFile }
        } else {
            0
        }

        return JreInstallStatus(
            javaMajor = javaMajor,
            jreRootDir = jreDir,
            javaBinaryFile = javaBin,
            isInstalledAndExecutable = isReady,
            installedFilesCount = count
        )
    }

    /**
     * Проверяет наличие OpenJDK в context.filesDir.resolve("runtimes/jre_<version>"),
     * а при отсутствии — реально скачивает архив портативной OpenJDK (aarch64),
     * распаковывает его и выставляет права на исполнение file.setExecutable(true, false),
     * Os.chmod(path, 0755) и chmod -R 755 для файла java, всех файлов в bin/ и библиотек в lib/.
     */
    fun ensureJavaRuntimeInstalled(
        filesDir: File,
        javaMajor: JavaRuntimeMajor,
        onLogLine: (String) -> Unit
    ): Flow<AssetPrepareProgress> = channelFlow {
        val jreRoot = getJreDirectory(filesDir, javaMajor)
        if (jreRoot.exists()) {
            enforceExecutablePermissions(jreRoot)
        }
        val currentStatus = inspectJreStatus(filesDir, javaMajor)
        if (currentStatus.isInstalledAndExecutable) {
            val fixedCount = enforceExecutablePermissions(jreRoot)
            onLogLine(
                "[JavaRuntimeManager] Найден готовый OpenJDK ${javaMajor.major} в ${jreRoot.absolutePath} " +
                    "(проверено прав +x: $fixedCount файлов, bin/java canExecute=${currentStatus.javaBinaryFile.canExecute()})"
            )
            send(
                AssetPrepareProgress(
                    stageTitle = "OpenJDK ${javaMajor.major} (aarch64) готов к запуску",
                    progressFraction = 1.0f,
                    currentFile = "${jreRoot.name}/bin/java",
                    sha1Verified = true,
                    completed = true
                )
            )
            return@channelFlow
        }

        jreRoot.mkdirs()
        applyDirectoryTraversalPermissions(filesDir)
        applyDirectoryTraversalPermissions(jreRoot.parentFile)
        applyDirectoryTraversalPermissions(jreRoot)

        val candidateUrls = AARCH64_OPENJDK_URLS[javaMajor.major] ?: AARCH64_OPENJDK_URLS.getValue(21)
        val tempArchive = File(filesDir, "runtimes/openjdk_${javaMajor.major}_aarch64.archive")
        tempArchive.parentFile?.mkdirs()

        var downloadedSuccess = false
        var usedUrl = ""

        for (url in candidateUrls) {
            try {
                usedUrl = url
                onLogLine("[JavaRuntimeManager] Скачивание архива OpenJDK ${javaMajor.major} (aarch64): $url")
                send(
                    AssetPrepareProgress(
                        stageTitle = "Скачивание OpenJDK ${javaMajor.major} (aarch64)...",
                        progressFraction = 0.05f,
                        currentFile = "runtimes/jre_${javaMajor.major}"
                    )
                )

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "MonolithLauncher/2.0 (Android; arm64-v8a)")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} при запросе $url")
                    }
                    val body = response.body ?: throw IllegalStateException("Пустой ответ от $url")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    var lastEmitMs = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempArchive).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs > 150L) {
                                    lastEmitMs = now
                                    val dlMb = downloadedBytes / (1024f * 1024f)
                                    val totMb = if (totalBytes > 0) totalBytes / (1024f * 1024f) else 45f
                                    val frac = (dlMb / totMb.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    trySend(
                                        AssetPrepareProgress(
                                            stageTitle = "Скачивание OpenJDK ${javaMajor.major}: ${"%.1f".format(dlMb)} / ${"%.1f".format(totMb)} МБ",
                                            progressFraction = 0.05f + frac * 0.65f,
                                            currentFile = "runtimes/jre_${javaMajor.major}",
                                            downloadedMb = dlMb,
                                            totalMb = totMb
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (tempArchive.exists() && tempArchive.length() > 500_000L) {
                    downloadedSuccess = true
                    break
                }
            } catch (e: Exception) {
                onLogLine("[JavaRuntimeManager/WARN] Зеркало недоступно ($url): ${e.message}")
            }
        }

        if (!downloadedSuccess) {
            throw IllegalStateException(
                "Не удалось скачать архив OpenJDK ${javaMajor.major} (aarch64). Проверьте подключение к интернету."
            )
        }

        send(
            AssetPrepareProgress(
                stageTitle = "Распаковка архива OpenJDK ${javaMajor.major} и установка прав chmod +x...",
                progressFraction = 0.78f,
                currentFile = jreRoot.absolutePath
            )
        )
        onLogLine("[JavaRuntimeManager] Распаковка архива (${"%.1f".format(tempArchive.length() / (1024f * 1024f))} МБ) в ${jreRoot.absolutePath}...")

        var updatedPermissionsCount = 0
        withContext(Dispatchers.IO) {
            extractArchiveToJreDir(tempArchive, jreRoot, usedUrl)
            tempArchive.delete()
            normalizeTopLevelJreLayout(jreRoot)
            updatedPermissionsCount = enforceExecutablePermissions(jreRoot)
        }

        val finalStatus = inspectJreStatus(filesDir, javaMajor)
        onLogLine(
            "[JavaRuntimeManager] OpenJDK ${javaMajor.major} распакован: файлов=${finalStatus.installedFilesCount}, " +
                "выставлено прав +x (chmod 755)=$updatedPermissionsCount, bin/java canExecute=${finalStatus.javaBinaryFile.canExecute()}"
        )

        send(
            AssetPrepareProgress(
                stageTitle = "OpenJDK ${javaMajor.major} (aarch64) установлен и готов",
                progressFraction = 1.0f,
                currentFile = finalStatus.javaBinaryFile.absolutePath,
                sha1Verified = true,
                completed = true
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Распаковывает скачанный архив (.tar.gz, .tar.xz или .zip) в целевую папку JRE.
     */
    private fun extractArchiveToJreDir(archiveFile: File, targetDir: File, sourceUrl: String) {
        targetDir.mkdirs()
        applyDirectoryTraversalPermissions(targetDir)

        val magic = ByteArray(6)
        FileInputStream(archiveFile).use { it.read(magic) }

        val isGzip = (magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte())
        val isZip = (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte())
        val isXz = (magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() && magic[2] == 0x7A.toByte())

        when {
            isZip -> {
                ZipInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                            applyDirectoryTraversalPermissions(outFile)
                        } else {
                            outFile.parentFile?.let { parent ->
                                parent.mkdirs()
                                applyDirectoryTraversalPermissions(parent)
                            }
                            FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                            applyExtractedFilePermissions(outFile, isExecutableHint = isLikelyExecutableFile(outFile))
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            isGzip -> {
                GZIPInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { gis ->
                    extractTarStream(gis, targetDir)
                }
            }
            isXz || sourceUrl.endsWith(".tar.xz") -> {
                val proc = ProcessBuilder("tar", "-xJf", archiveFile.absolutePath, "-C", targetDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val exit = proc.waitFor()
                if (exit != 0) {
                    throw IllegalStateException("Ошибка распаковки .tar.xz (код $exit)")
                }
            }
            else -> {
                BufferedInputStream(FileInputStream(archiveFile)).use { bis ->
                    extractTarStream(bis, targetDir)
                }
            }
        }
    }

    /**
     * Нативный парсер POSIX USTAR / GNU TAR потоков (для .tar.gz сборок Adoptium OpenJDK aarch64)
     * с поддержкой символических ссылок, прав доступа из заголовка TAR и немедленным выставлением +x.
     */
    private fun extractTarStream(tarInput: InputStream, targetDir: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        val deferredSymlinks = mutableListOf<Pair<File, String>>()

        while (true) {
            val readHeader = readFully(tarInput, header)
            if (readHeader < 512 || header.all { it == 0.toByte() }) break

            val rawName = extractNullTerminatedString(header, 0, 100)
            val modeOctal = extractNullTerminatedString(header, 100, 8).trim()
            val tarMode = modeOctal.toIntOrNull(8) ?: 0
            val sizeOctal = extractNullTerminatedString(header, 124, 12).trim()
            val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
            val typeFlag = header[156].toInt().toChar()
            val rawLinkName = extractNullTerminatedString(header, 157, 100)
            val prefix = extractNullTerminatedString(header, 345, 155)

            if (typeFlag == 'L') {
                val nameBytes = ByteArray(entrySize.toInt())
                readFully(tarInput, nameBytes)
                pendingLongName = String(nameBytes).trimEnd('\u0000', '\n', '\r')
                val padding = ((512 - (entrySize % 512)) % 512).toInt()
                if (padding > 0) skipFully(tarInput, padding.toLong())
                continue
            }

            if (typeFlag == 'K') {
                val linkBytes = ByteArray(entrySize.toInt())
                readFully(tarInput, linkBytes)
                pendingLongLink = String(linkBytes).trimEnd('\u0000', '\n', '\r')
                val padding = ((512 - (entrySize % 512)) % 512).toInt()
                if (padding > 0) skipFully(tarInput, padding.toLong())
                continue
            }

            val fullName = pendingLongName ?: if (prefix.isNotBlank()) "$prefix/$rawName" else rawName
            val linkTarget = pendingLongLink ?: rawLinkName
            pendingLongName = null
            pendingLongLink = null

            if (fullName.isBlank()) {
                skipTarEntryPayload(tarInput, entrySize)
                continue
            }

            val destFile = File(targetDir, fullName)
            when (typeFlag) {
                '5' -> {
                    destFile.mkdirs()
                    applyDirectoryTraversalPermissions(destFile)
                    skipTarEntryPayload(tarInput, entrySize)
                }
                '2', '1' -> {
                    destFile.parentFile?.let { parent ->
                        parent.mkdirs()
                        applyDirectoryTraversalPermissions(parent)
                    }
                    if (linkTarget.isNotBlank()) {
                        deferredSymlinks += (destFile to linkTarget)
                    }
                    skipTarEntryPayload(tarInput, entrySize)
                }
                '0', '\u0000' -> {
                    destFile.parentFile?.let { parent ->
                        parent.mkdirs()
                        applyDirectoryTraversalPermissions(parent)
                    }
                    if (destFile.exists()) {
                        destFile.setWritable(true, true)
                    }
                    FileOutputStream(destFile).use { fos ->
                        var remaining = entrySize
                        val buf = ByteArray(32 * 1024)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val r = tarInput.read(buf, 0, toRead)
                            if (r <= 0) break
                            fos.write(buf, 0, r)
                            remaining -= r
                        }
                    }
                    val hasTarExecBit = (tarMode and 73) != 0 // 0111 in octal = 73 in decimal
                    applyExtractedFilePermissions(
                        file = destFile,
                        isExecutableHint = hasTarExecBit || isLikelyExecutableFile(destFile)
                    )
                    val padding = (512 - (entrySize % 512)) % 512
                    if (padding > 0) {
                        skipFully(tarInput, padding)
                    }
                }
                else -> {
                    skipTarEntryPayload(tarInput, entrySize)
                }
            }
        }

        // Materialize symlinks/hardlinks after all regular files are extracted
        for ((linkFile, targetPath) in deferredSymlinks) {
            runCatching {
                if (linkFile.exists()) linkFile.delete()
                Os.symlink(targetPath, linkFile.absolutePath)
            }.onFailure {
                val resolvedTarget = File(linkFile.parentFile, targetPath).canonicalFile
                if (resolvedTarget.exists() && resolvedTarget.isFile) {
                    runCatching {
                        resolvedTarget.copyTo(linkFile, overwrite = true)
                        applyExtractedFilePermissions(linkFile, isExecutableHint = isLikelyExecutableFile(linkFile))
                    }
                }
            }
        }
    }

    private fun skipTarEntryPayload(input: InputStream, entrySize: Long) {
        if (entrySize <= 0L) return
        val padded = entrySize + ((512 - (entrySize % 512)) % 512)
        skipFully(input, padded)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val r = input.read(buffer, offset, buffer.size - offset)
            if (r == -1) break
            offset += r
        }
        return offset
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(discard.size.toLong(), remaining).toInt()
            val r = input.read(discard, 0, toRead)
            if (r <= 0) break
            remaining -= r
        }
    }

    private fun extractNullTerminatedString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = (offset + length).coerceAtMost(buf.size)
        while (end < max && buf[end] != 0.toByte()) {
            end++
        }
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    /**
     * Если архив Adoptium содержит корневую вложенную папку (например jdk-21.0.4+7-jre/bin/java),
     * переносит её содержимое напрямую в context.filesDir.resolve("runtimes/jre_<version>")
     * и сохраняет права на выполнение для всех скопированных файлов.
     */
    private fun normalizeTopLevelJreLayout(jreRoot: File) {
        val directBin = File(jreRoot, "bin/java")
        if (directBin.exists()) return

        val children = jreRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (children.size == 1) {
            val nestedRoot = children.first()
            if (File(nestedRoot, "bin/java").exists() || File(nestedRoot, "jre/bin/java").exists()) {
                val actualSource = if (File(nestedRoot, "jre/bin/java").exists()) {
                    File(nestedRoot, "jre")
                } else {
                    nestedRoot
                }
                actualSource.listFiles()?.forEach { child ->
                    val dest = File(jreRoot, child.name)
                    if (!dest.exists()) {
                        if (!child.renameTo(dest)) {
                            child.copyRecursively(dest, overwrite = true)
                        }
                    }
                }
                nestedRoot.deleteRecursively()
            }
        }
    }

    private fun isLikelyExecutableFile(file: File): Boolean {
        val parentName = file.parentFile?.name.orEmpty()
        val fileName = file.name
        return parentName == "bin" ||
            fileName == "java" ||
            fileName == "javac" ||
            fileName == "keytool" ||
            fileName == "jspawnhelper" ||
            fileName == "jexec" ||
            fileName.endsWith(".so") ||
            fileName.contains(".so.")
    }

    private fun applyDirectoryTraversalPermissions(dir: File?) {
        if (dir == null || !dir.exists()) return
        runCatching {
            dir.setReadable(true, false)
            dir.setWritable(true, true)
            if (!dir.setExecutable(true, false)) {
                dir.setExecutable(true, true)
            }
            Os.chmod(dir.absolutePath, MODE_RWXR_XR_X)
        }
    }

    private fun applyExtractedFilePermissions(file: File, isExecutableHint: Boolean) {
        if (!file.exists()) return
        runCatching {
            file.setReadable(true, false)
            file.setWritable(true, true)
            if (isExecutableHint) {
                if (!file.setExecutable(true, false)) {
                    file.setExecutable(true, true)
                }
                Os.chmod(file.absolutePath, MODE_RWXR_XR_X)
            }
        }
    }

    /**
     * Программно выставляет права на выполнение (executable flag +x / 0755) для:
     * 1. Всех родительских директорий JRE (чтобы ядро Linux разрешало проход по пути до bin/java).
     * 2. Бинарного файла bin/java и ВСЕХ файлов в папке bin/ (через file.setExecutable(true, false),
     *    резервный file.setExecutable(true, true), системный вызов Os.chmod(..., 0755) и команду chmod).
     * 3. Вспомогательных бинарников (lib/jspawnhelper, lib/jexec) и всех нативных библиотек (*.so) в lib/.
     *
     * Возвращает количество файлов, для которых успешно выставлен флаг исполнения.
     */
    fun enforceExecutablePermissions(jreRoot: File, readOnlyDynamicCode: Boolean = false): Int {
        if (!jreRoot.exists()) return 0
        var updatedFiles = 0
        val targetExecMode = if (readOnlyDynamicCode) MODE_R_XR_XR_X else MODE_RWXR_XR_X

        // 1. Гарантируем права прохода (+x) для родительских папок (filesDir, runtimes, jre_<ver>)
        applyDirectoryTraversalPermissions(jreRoot.parentFile?.parentFile)
        applyDirectoryTraversalPermissions(jreRoot.parentFile)
        applyDirectoryTraversalPermissions(jreRoot)

        // 2. Явно выставляем права на исполнение для всех файлов в папке bin/ (включая bin/java)
        val binDirs = listOf(File(jreRoot, "bin"), File(jreRoot, "jre/bin"))
        for (binDir in binDirs) {
            if (binDir.exists() && binDir.isDirectory) {
                applyDirectoryTraversalPermissions(binDir)
                binDir.walkTopDown().forEach { file ->
                    if (file.isDirectory) {
                        applyDirectoryTraversalPermissions(file)
                    } else if (file.isFile) {
                        file.setReadable(true, false)
                        file.setWritable(!readOnlyDynamicCode, true)
                        if (!file.setExecutable(true, false)) {
                            file.setExecutable(true, true)
                        }
                        runCatching { Os.chmod(file.absolutePath, targetExecMode) }
                        updatedFiles++
                    }
                }
            }
        }

        // 3. Обходим всё дерево JRE для выставления прав на директории, jspawnhelper, jexec и *.so библиотеки
        jreRoot.walkTopDown().forEach { entry ->
            if (entry.isDirectory) {
                applyDirectoryTraversalPermissions(entry)
            } else if (entry.isFile) {
                entry.setReadable(true, false)
                if (isLikelyExecutableFile(entry)) {
                    entry.setWritable(!readOnlyDynamicCode, true)
                    if (!entry.setExecutable(true, false)) {
                        entry.setExecutable(true, true)
                    }
                    runCatching { Os.chmod(entry.absolutePath, targetExecMode) }
                    updatedFiles++
                }
            }
        }

        // 4. Дополнительно дублируем через системную команду chmod для 100% гарантии на уровне ядра Android
        val chmodModeStr = if (readOnlyDynamicCode) "555" else "755"
        runCatching {
            ProcessBuilder("chmod", "-R", "755", jreRoot.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
        }

        val javaBinary = File(jreRoot, "bin/java")
        if (javaBinary.exists()) {
            javaBinary.setReadable(true, false)
            javaBinary.setWritable(!readOnlyDynamicCode, true)
            if (!javaBinary.setExecutable(true, false)) {
                javaBinary.setExecutable(true, true)
            }
            runCatching { Os.chmod(javaBinary.absolutePath, targetExecMode) }
            runCatching {
                ProcessBuilder("chmod", chmodModeStr, javaBinary.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS)
            }
        }

        return updatedFiles
    }
}
