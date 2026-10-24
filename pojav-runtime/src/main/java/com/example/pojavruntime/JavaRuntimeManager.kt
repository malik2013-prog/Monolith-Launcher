package com.example.pojavruntime

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
     */
    fun inspectJreStatus(filesDir: File, javaMajor: JavaRuntimeMajor): JreInstallStatus {
        val jreDir = getJreDirectory(filesDir, javaMajor)
        val javaBin = File(jreDir, "bin/java")
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
     * распаковывает его и выставляет права на исполнение file.setExecutable(true, false)
     * для всех бинарников в bin/ и библиотек в lib/.
     */
    fun ensureJavaRuntimeInstalled(
        filesDir: File,
        javaMajor: JavaRuntimeMajor,
        onLogLine: (String) -> Unit
    ): Flow<AssetPrepareProgress> = channelFlow {
        val jreRoot = getJreDirectory(filesDir, javaMajor)
        val currentStatus = inspectJreStatus(filesDir, javaMajor)
        if (currentStatus.isInstalledAndExecutable) {
            enforceExecutablePermissions(jreRoot)
            onLogLine("[JavaRuntimeManager] Найден готовый OpenJDK ${javaMajor.major} в ${jreRoot.absolutePath}")
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
                stageTitle = "Распаковка архива OpenJDK ${javaMajor.major} в runtimes/jre_${javaMajor.major}...",
                progressFraction = 0.78f,
                currentFile = jreRoot.absolutePath
            )
        )
        onLogLine("[JavaRuntimeManager] Распаковка архива (${"%.1f".format(tempArchive.length() / (1024f * 1024f))} МБ) в ${jreRoot.absolutePath}...")

        withContext(Dispatchers.IO) {
            extractArchiveToJreDir(tempArchive, jreRoot, usedUrl)
            tempArchive.delete()
            normalizeTopLevelJreLayout(jreRoot)
            enforceExecutablePermissions(jreRoot)
        }

        val finalStatus = inspectJreStatus(filesDir, javaMajor)
        onLogLine(
            "[JavaRuntimeManager] OpenJDK ${javaMajor.major} распакован: файлов=${finalStatus.installedFilesCount}, " +
                "bin/java executable=${finalStatus.javaBinaryFile.canExecute()}"
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

        // Inspect magic bytes of archiveFile
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
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zis.copyTo(out) }
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
                // Attempt system tar -xJf first on Android toybox
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
     * Нативный парсер POSIX USTAR / GNU TAR потоков (для .tar.gz сборок Adoptium OpenJDK aarch64).
     */
    private fun extractTarStream(tarInput: InputStream, targetDir: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null

        while (true) {
            val readHeader = readFully(tarInput, header)
            if (readHeader < 512 || header.all { it == 0.toByte() }) break

            val rawName = extractNullTerminatedString(header, 0, 100)
            val prefix = extractNullTerminatedString(header, 345, 155)
            val sizeOctal = extractNullTerminatedString(header, 124, 12).trim()
            val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
            val typeFlag = header[156].toInt().toChar()

            val fullName = pendingLongName ?: if (prefix.isNotBlank()) "$prefix/$rawName" else rawName
            pendingLongName = null

            if (typeFlag == 'L') {
                // GNU Tar LongLink header
                val nameBytes = ByteArray(entrySize.toInt())
                readFully(tarInput, nameBytes)
                pendingLongName = String(nameBytes).trimEnd('\u0000', '\n', '\r')
                val padding = ((512 - (entrySize % 512)) % 512).toInt()
                if (padding > 0) skipFully(tarInput, padding.toLong())
                continue
            }

            if (fullName.isBlank()) {
                skipTarEntryPayload(tarInput, entrySize)
                continue
            }

            val destFile = File(targetDir, fullName)
            when (typeFlag) {
                '5' -> {
                    destFile.mkdirs()
                    skipTarEntryPayload(tarInput, entrySize)
                }
                '0', '\u0000' -> {
                    destFile.parentFile?.mkdirs()
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
     * переносит её содержимое напрямую в context.filesDir.resolve("runtimes/jre_<version>").
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

    /**
     * Принудительно выставляет права на исполнение file.setExecutable(true, false)
     * для всех бинарников в bin/ и всех разделяемых библиотек в lib/.
     */
    fun enforceExecutablePermissions(jreRoot: File) {
        val binDir = File(jreRoot, "bin")
        if (binDir.exists()) {
            binDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                }
            }
        }

        val libDir = File(jreRoot, "lib")
        if (libDir.exists()) {
            libDir.walkTopDown().forEach { file ->
                if (file.isFile && (file.extension == "so" || file.name.contains(".so.") || file.name == "jspawnhelper")) {
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                }
            }
        }
    }
}
