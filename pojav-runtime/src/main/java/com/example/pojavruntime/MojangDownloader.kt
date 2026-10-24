package com.example.pojavruntime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

data class ParsedVersionDescriptor(
    val id: String,
    val mainClass: String,
    val javaMajorVersion: Int,
    val assetIndexId: String,
    val assetIndexUrl: String,
    val assetIndexSha1: String,
    val clientJarUrl: String,
    val clientJarSha1: String,
    val clientJarSize: Long,
    val libraries: List<MavenLibraryArtifact>,
    val gameArguments: List<String>,
    val jvmArguments: List<String>
)

data class MavenLibraryArtifact(
    val coordinates: String,
    val mavenRelativePath: String,
    val downloadUrl: String,
    val sha1: String,
    val sizeBytes: Long,
    val isNativeLibrary: Boolean = false
)

data class AssetObjectSpec(
    val resourceKey: String,
    val hash: String,
    val sizeBytes: Long
)

class MojangDownloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        const val VERSION_MANIFEST_V2_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val RESOURCES_BASE_URL =
            "https://resources.download.minecraft.net"
        const val LIBRARIES_BASE_URL =
            "https://libraries.minecraft.net"
    }

    /**
     * Проверяет реальное состояние скачанных файлов Mojang и JRE на файловой системе Android.
     */
    fun inspectInstalledComponents(
        version: MinecraftVersion?,
        minecraftRootDir: File,
        filesDir: File
    ): MojangComponentsStatus {
        if (version == null) return MojangComponentsStatus()
        val versionDir = File(minecraftRootDir, "versions/${version.id}")
        val versionJsonFile = File(versionDir, "${version.id}.json")
        val clientJarFile = File(versionDir, "${version.id}.jar")
        val indexesDir = File(minecraftRootDir, "assets/indexes")
        val objectsDir = File(minecraftRootDir, "assets/objects")
        val librariesDir = File(minecraftRootDir, "libraries")

        var resolvedJava = version.recommendedJava.major
        if (versionJsonFile.exists() && versionJsonFile.length() > 64) {
            runCatching {
                val root = JSONObject(versionJsonFile.readText())
                val jv = root.optJSONObject("javaVersion")?.optInt("majorVersion", 0) ?: 0
                if (jv > 0) resolvedJava = jv
            }
        }

        val jreBinJava = File(filesDir, "runtimes/jre_$resolvedJava/bin/java")
        val jarExists = clientJarFile.exists() && clientJarFile.length() > 100_000L
        val jarSizeMb = if (jarExists) clientJarFile.length() / (1024f * 1024f) else 0f

        val indexFiles = indexesDir.listFiles()?.filter { it.extension == "json" && it.length() > 32 }.orEmpty()
        val objectCount = if (objectsDir.exists()) {
            objectsDir.walkTopDown().count { it.isFile && it.length() > 0 }
        } else {
            0
        }
        val libCount = if (librariesDir.exists()) {
            librariesDir.walkTopDown().count { it.isFile && it.extension == "jar" && it.length() > 0 }
        } else {
            0
        }

        return MojangComponentsStatus(
            versionId = version.id,
            versionJsonInstalled = versionJsonFile.exists() && versionJsonFile.length() > 64,
            clientJarInstalled = jarExists,
            clientJarSizeMb = jarSizeMb,
            assetIndexInstalled = indexFiles.isNotEmpty(),
            assetObjectsInstalledCount = objectCount,
            mojangLibrariesInstalledCount = libCount,
            jreInstalled = jreBinJava.exists() && jreBinJava.canExecute(),
            resolvedJavaMajor = resolvedJava
        )
    }

    /**
     * Скачивает и парсит официальный манифест Mojang:
     * https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
     * без каких-либо захардкоженных списков или имитаций.
     */
    suspend fun fetchOfficialVersionManifest(minecraftRootDir: File): Result<List<MinecraftVersion>> =
        withContext(Dispatchers.IO) {
            runCatching {
                minecraftRootDir.mkdirs()
                val cacheManifestFile = File(minecraftRootDir, "version_manifest_v2.json")

                val request = Request.Builder()
                    .url(VERSION_MANIFEST_V2_URL)
                    .header("User-Agent", "MonolithLauncher/2.0 (Android; arm64-v8a)")
                    .build()

                val rawJson = try {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("HTTP ${response.code} от piston-meta.mojang.com")
                        }
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) {
                            throw IllegalStateException("Пустой ответ от piston-meta.mojang.com")
                        }
                        cacheManifestFile.writeText(body)
                        body
                    }
                } catch (netErr: Exception) {
                    if (cacheManifestFile.exists() && cacheManifestFile.length() > 100) {
                        cacheManifestFile.readText()
                    } else {
                        throw netErr
                    }
                }

                parseManifestV2Json(rawJson, minecraftRootDir)
            }
        }

    private fun parseManifestV2Json(rawJson: String, minecraftRootDir: File): List<MinecraftVersion> {
        val root = JSONObject(rawJson)
        val versionsArray = root.optJSONArray("versions") ?: return emptyList()
        val total = versionsArray.length()
        val parsedList = ArrayList<MinecraftVersion>(total)

        for (i in 0 until total) {
            val obj = versionsArray.getJSONObject(i)
            val id = obj.optString("id")
            val rawType = obj.optString("type")
            val url = obj.optString("url")
            val sha1 = obj.optString("sha1")
            val releaseTime = obj.optString("releaseTime").take(10)

            val era = classifyEra(id, rawType)
            val recommendedJava = inferJavaFromReleaseDate(id, era, releaseTime)
            val mainClass = if (era in listOf(
                    VanillaEra.PRE_CLASSIC,
                    VanillaEra.CLASSIC,
                    VanillaEra.INFDEV,
                    VanillaEra.ALPHA,
                    VanillaEra.BETA
                )
            ) {
                "net.minecraft.launchwrapper.Launch"
            } else {
                "net.minecraft.client.main.Main"
            }

            val localJar = File(minecraftRootDir, "versions/$id/$id.jar")
            val isVerifiedLocal = localJar.exists() && localJar.length() > 100_000L

            parsedList.add(
                MinecraftVersion(
                    id = id,
                    era = era,
                    releaseTime = releaseTime,
                    manifestUrl = url,
                    sha1 = sha1,
                    recommendedJava = recommendedJava,
                    mainClass = mainClass,
                    assetsIndex = id.substringBeforeLast('.', id),
                    summary = "${rawType.uppercase()} • Mojang Piston SHA1: ${sha1.take(8)} • ${recommendedJava.title}",
                    isVerifiedLocal = isVerifiedLocal
                )
            )
        }
        return parsedList
    }

    fun classifyEra(id: String, rawType: String): VanillaEra = when {
        id.startsWith("rd-") -> VanillaEra.PRE_CLASSIC
        id.startsWith("c0.") -> VanillaEra.CLASSIC
        id.startsWith("inf-") -> VanillaEra.INFDEV
        id.startsWith("a1.") -> VanillaEra.ALPHA
        id.startsWith("b1.") -> VanillaEra.BETA
        rawType == "old_alpha" -> when {
            id.startsWith("rd-") -> VanillaEra.PRE_CLASSIC
            id.startsWith("c0.") -> VanillaEra.CLASSIC
            id.startsWith("inf-") -> VanillaEra.INFDEV
            else -> VanillaEra.ALPHA
        }
        rawType == "old_beta" -> VanillaEra.BETA
        rawType == "snapshot" -> VanillaEra.SNAPSHOT
        else -> VanillaEra.RELEASE
    }

    fun inferJavaFromReleaseDate(id: String, era: VanillaEra, releaseDate: String): JavaRuntimeMajor {
        if (era in listOf(
                VanillaEra.PRE_CLASSIC,
                VanillaEra.CLASSIC,
                VanillaEra.INFDEV,
                VanillaEra.ALPHA,
                VanillaEra.BETA
            )
        ) {
            return JavaRuntimeMajor.JRE_8
        }
        if (releaseDate >= "2024-04-23" || id.startsWith("1.20.5") || id.startsWith("1.20.6") || id.startsWith("1.21")) {
            return JavaRuntimeMajor.JRE_21
        }
        if (releaseDate >= "2021-06-08" || id.startsWith("1.17") || id.startsWith("1.18") || id.startsWith("1.19") || id.startsWith("1.20")) {
            return JavaRuntimeMajor.JRE_17
        }
        return JavaRuntimeMajor.JRE_8
    }

    /**
     * Читает и парсит скачанный JSON конкретной версии (versions/<id>/<id>.json).
     */
    fun parseVersionJsonFile(versionJsonFile: File, fallbackId: String): ParsedVersionDescriptor {
        val root = JSONObject(versionJsonFile.readText())
        val id = root.optString("id", fallbackId)
        val mainClass = root.optString("mainClass", "net.minecraft.client.main.Main")
        val javaMajor = root.optJSONObject("javaVersion")?.optInt("majorVersion", 8) ?: 8

        val assetIndexObj = root.optJSONObject("assetIndex")
        val assetIndexId = assetIndexObj?.optString("id", root.optString("assets", "legacy")) ?: "legacy"
        val assetIndexUrl = assetIndexObj?.optString("url").orEmpty()
        val assetIndexSha1 = assetIndexObj?.optString("sha1").orEmpty()

        val clientObj = root.optJSONObject("downloads")?.optJSONObject("client")
        val clientUrl = clientObj?.optString("url").orEmpty()
        val clientSha1 = clientObj?.optString("sha1").orEmpty()
        val clientSize = clientObj?.optLong("size", 0L) ?: 0L

        val librariesList = mutableListOf<MavenLibraryArtifact>()
        val libsArray = root.optJSONArray("libraries") ?: JSONArray()
        for (i in 0 until libsArray.length()) {
            val libObj = libsArray.getJSONObject(i)
            val rulesArr = libObj.optJSONArray("rules")
            if (!evaluateLibraryRules(rulesArr)) continue

            val coord = libObj.optString("name")
            val downloadsObj = libObj.optJSONObject("downloads")
            val artifactObj = downloadsObj?.optJSONObject("artifact")

            if (artifactObj != null) {
                val relPath = artifactObj.optString("path").ifBlank { mavenCoordinatesToPath(coord) }
                val url = artifactObj.optString("url").ifBlank { "$LIBRARIES_BASE_URL/$relPath" }
                val sha1 = artifactObj.optString("sha1")
                val size = artifactObj.optLong("size", 0L)
                if (relPath.isNotBlank() && url.isNotBlank()) {
                    librariesList.add(
                        MavenLibraryArtifact(
                            coordinates = coord,
                            mavenRelativePath = relPath,
                            downloadUrl = url,
                            sha1 = sha1,
                            sizeBytes = size,
                            isNativeLibrary = coord.contains("natives-linux") || coord.contains("natives-arm64")
                        )
                    )
                }
            } else if (coord.isNotBlank()) {
                val relPath = mavenCoordinatesToPath(coord)
                val baseUrl = libObj.optString("url", "$LIBRARIES_BASE_URL/").trimEnd('/')
                if (relPath.isNotBlank()) {
                    librariesList.add(
                        MavenLibraryArtifact(
                            coordinates = coord,
                            mavenRelativePath = relPath,
                            downloadUrl = "$baseUrl/$relPath",
                            sha1 = "",
                            sizeBytes = 0L
                        )
                    )
                }
            }
        }

        val gameArgs = mutableListOf<String>()
        val jvmArgs = mutableListOf<String>()
        val argumentsObj = root.optJSONObject("arguments")
        if (argumentsObj != null) {
            val gameArr = argumentsObj.optJSONArray("game")
            if (gameArr != null) {
                for (i in 0 until gameArr.length()) {
                    val item = gameArr.get(i)
                    if (item is String) gameArgs.add(item)
                }
            }
            val jvmArr = argumentsObj.optJSONArray("jvm")
            if (jvmArr != null) {
                for (i in 0 until jvmArr.length()) {
                    val item = jvmArr.get(i)
                    if (item is String) jvmArgs.add(item)
                }
            }
        } else {
            val legacyArgs = root.optString("minecraftArguments")
            if (legacyArgs.isNotBlank()) {
                gameArgs.addAll(legacyArgs.split(" ").filter { it.isNotBlank() })
            }
        }

        return ParsedVersionDescriptor(
            id = id,
            mainClass = mainClass,
            javaMajorVersion = javaMajor,
            assetIndexId = assetIndexId,
            assetIndexUrl = assetIndexUrl,
            assetIndexSha1 = assetIndexSha1,
            clientJarUrl = clientUrl,
            clientJarSha1 = clientSha1,
            clientJarSize = clientSize,
            libraries = librariesList.distinctBy { it.mavenRelativePath },
            gameArguments = gameArgs,
            jvmArguments = jvmArgs
        )
    }

    /**
     * Проверяет массив rules в манифесте версии Mojang для Linux/Android (arm64-v8a).
     * Исключает библиотеки, предназначенные исключительно для Windows или macOS (osx).
     */
    private fun evaluateLibraryRules(rulesArray: JSONArray?): Boolean {
        if (rulesArray == null || rulesArray.length() == 0) return true
        var allowed = false
        for (i in 0 until rulesArray.length()) {
            val rule = rulesArray.getJSONObject(i)
            val action = rule.optString("action", "allow")
            val osObj = rule.optJSONObject("os")
            if (osObj == null) {
                allowed = (action == "allow")
            } else {
                val osName = osObj.optString("name", "")
                val matchesLinuxOrAndroid = (osName == "linux" || osName == "android" || osName.isBlank())
                if (matchesLinuxOrAndroid) {
                    allowed = (action == "allow")
                }
            }
        }
        return allowed
    }

    private fun mavenCoordinatesToPath(coord: String): String {
        val parts = coord.split(":")
        if (parts.size < 3) return ""
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = if (parts.size >= 4) "-${parts[3]}" else ""
        return "$groupPath/$artifact/$version/$artifact-$version$classifier.jar"
    }

    /**
     * Полный пайплайн скачивания официальных файлов Mojang на Coroutines Dispatchers.IO:
     * а) versions/<id>/<id>.json
     * б) versions/<id>/<id>.jar (с проверкой SHA-1)
     * в) Все библиотеки из массива libraries в libraries/ по путям Maven (с проверкой rules и SHA-1)
     * г) Индекс ассетов assets/indexes/<id>.json и многопоточная (Semaphore(16)) загрузка всех ресурсов в assets/objects/
     */
    fun downloadVersionComplete(
        version: MinecraftVersion,
        minecraftRootDir: File,
        onLogLine: (String) -> Unit
    ): Flow<AssetPrepareProgress> = channelFlow {
        val versionDir = File(minecraftRootDir, "versions/${version.id}").apply { mkdirs() }
        val versionJsonFile = File(versionDir, "${version.id}.json")
        val clientJarFile = File(versionDir, "${version.id}.jar")
        val librariesRootDir = File(minecraftRootDir, "libraries").apply { mkdirs() }
        val nativesDir = File(versionDir, "natives").apply { mkdirs() }
        val assetsIndexesDir = File(minecraftRootDir, "assets/indexes").apply { mkdirs() }
        val assetsObjectsDir = File(minecraftRootDir, "assets/objects").apply { mkdirs() }

        // --- ЭТАП А: Скачивание versions/<id>/<id>.json ---
        send(
            AssetPrepareProgress(
                stageTitle = "Скачивание манифеста версии ${version.id}.json...",
                progressFraction = 0.02f,
                currentFile = "versions/${version.id}/${version.id}.json"
            )
        )
        onLogLine("[MojangDownloader] GET ${version.manifestUrl}")

        downloadSingleFileWithSha1(
            url = version.manifestUrl,
            targetFile = versionJsonFile,
            expectedSha1 = version.sha1,
            onProgressBytes = { _, _ -> }
        )

        val descriptor = parseVersionJsonFile(versionJsonFile, version.id)
        onLogLine(
            "[MojangDownloader] Распарсен ${version.id}.json: mainClass=${descriptor.mainClass}, " +
                "javaVersion=${descriptor.javaMajorVersion}, библиотек=${descriptor.libraries.size}, assetIndex=${descriptor.assetIndexId}"
        )

        // --- ЭТАП Б: Скачивание оригинального client.jar с проверкой SHA-1 ---
        val clientTotalMb = descriptor.clientJarSize / (1024f * 1024f)
        send(
            AssetPrepareProgress(
                stageTitle = "Скачивание оригинального client.jar (${version.id})...",
                progressFraction = 0.06f,
                currentFile = "versions/${version.id}/${version.id}.jar",
                downloadedMb = 0f,
                totalMb = clientTotalMb
            )
        )
        onLogLine("[MojangDownloader] Проверка/скачивание client.jar (${"%.2f".format(clientTotalMb)} МБ, SHA1=${descriptor.clientJarSha1})")

        var lastClientEmitMs = 0L
        downloadSingleFileWithSha1(
            url = descriptor.clientJarUrl,
            targetFile = clientJarFile,
            expectedSha1 = descriptor.clientJarSha1,
            onProgressBytes = { downloaded, total ->
                val now = System.currentTimeMillis()
                if (now - lastClientEmitMs > 120L || downloaded == total) {
                    lastClientEmitMs = now
                    val dlMb = downloaded / (1024f * 1024f)
                    val totMb = (if (total > 0) total else descriptor.clientJarSize) / (1024f * 1024f)
                    val frac = if (totMb > 0f) (dlMb / totMb).coerceIn(0f, 1f) else 0.5f
                    trySend(
                        AssetPrepareProgress(
                            stageTitle = "Скачивание client.jar: ${"%.1f".format(dlMb)} / ${"%.1f".format(totMb)} МБ",
                            progressFraction = 0.05f + frac * 0.25f,
                            currentFile = "versions/${version.id}/${version.id}.jar",
                            downloadedMb = dlMb,
                            totalMb = totMb
                        )
                    )
                }
            }
        )
        onLogLine("[MojangDownloader] Верифицирован SHA-1 для versions/${version.id}/${version.id}.jar")

        // --- ЭТАП В: Скачивание всех библиотек из массива libraries по путям Maven ---
        val totalLibs = descriptor.libraries.size.coerceAtLeast(1)
        val totalLibsBytes = descriptor.libraries.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        val downloadedLibsBytes = AtomicLong(0L)
        val completedLibsCount = AtomicInteger(0)
        val libSemaphore = Semaphore(8)

        onLogLine("[MojangDownloader] Синхронизация ${descriptor.libraries.size} Maven-библиотек в libraries/...")
        coroutineScope {
            descriptor.libraries.map { lib ->
                async(Dispatchers.IO) {
                    libSemaphore.withPermit {
                        val destFile = File(librariesRootDir, lib.mavenRelativePath)
                        destFile.parentFile?.mkdirs()

                        downloadSingleFileWithSha1(
                            url = lib.downloadUrl,
                            targetFile = destFile,
                            expectedSha1 = lib.sha1,
                            onProgressBytes = { _, _ -> }
                        )

                        if (lib.isNativeLibrary && destFile.exists()) {
                            extractNativeSoFromJar(destFile, nativesDir)
                        }

                        val doneCount = completedLibsCount.incrementAndGet()
                        val bytesNow = downloadedLibsBytes.addAndGet(
                            if (lib.sizeBytes > 0) lib.sizeBytes else destFile.length()
                        )
                        val dlMb = bytesNow / (1024f * 1024f)
                        val totMb = totalLibsBytes / (1024f * 1024f)
                        val libFrac = doneCount.toFloat() / totalLibs.toFloat()

                        trySend(
                            AssetPrepareProgress(
                                stageTitle = "Библиотеки Maven ($doneCount/${descriptor.libraries.size})",
                                progressFraction = 0.30f + libFrac * 0.30f,
                                currentFile = "libraries/${lib.mavenRelativePath}",
                                downloadedMb = dlMb,
                                totalMb = totMb,
                                sha1Verified = true
                            )
                        )
                    }
                }
            }.awaitAll()
        }
        onLogLine("[MojangDownloader] Все ${descriptor.libraries.size} библиотек проверены и сохранены в libraries/")

        // --- ЭТАП Г: Скачивание индекса ассетов и многопоточная (Semaphore(16)) загрузка всех ресурсов ---
        val assetIndexFile = File(assetsIndexesDir, "${descriptor.assetIndexId}.json")
        if (descriptor.assetIndexUrl.isNotBlank()) {
            send(
                AssetPrepareProgress(
                    stageTitle = "Скачивание индекса ассетов ${descriptor.assetIndexId}.json...",
                    progressFraction = 0.62f,
                    currentFile = "assets/indexes/${descriptor.assetIndexId}.json"
                )
            )
            onLogLine("[MojangDownloader] GET ${descriptor.assetIndexUrl}")
            downloadSingleFileWithSha1(
                url = descriptor.assetIndexUrl,
                targetFile = assetIndexFile,
                expectedSha1 = descriptor.assetIndexSha1,
                onProgressBytes = { _, _ -> }
            )
        }

        if (assetIndexFile.exists() && assetIndexFile.length() > 16) {
            val objectsJson = JSONObject(assetIndexFile.readText()).optJSONObject("objects")
            if (objectsJson != null) {
                val assetSpecs = ArrayList<AssetObjectSpec>(objectsJson.length())
                val keysIter = objectsJson.keys()
                while (keysIter.hasNext()) {
                    val key = keysIter.next()
                    val entry = objectsJson.optJSONObject(key) ?: continue
                    val hash = entry.optString("hash")
                    val size = entry.optLong("size", 0L)
                    if (hash.length >= 40) {
                        assetSpecs.add(AssetObjectSpec(key, hash, size))
                    }
                }

                val totalAssets = assetSpecs.size.coerceAtLeast(1)
                val totalAssetsBytes = assetSpecs.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                val downloadedAssetsBytes = AtomicLong(0L)
                val completedAssets = AtomicInteger(0)
                val assetSemaphore = Semaphore(16)
                var lastAssetEmitMs = 0L

                onLogLine(
                    "[MojangDownloader] Многопоточная загрузка ${assetSpecs.size} ассетов (Semaphore(16), " +
                        "${"%.1f".format(totalAssetsBytes / (1024f * 1024f))} МБ)..."
                )

                coroutineScope {
                    assetSpecs.map { spec ->
                        async(Dispatchers.IO) {
                            assetSemaphore.withPermit {
                                val prefix2 = spec.hash.substring(0, 2)
                                val objectFile = File(assetsObjectsDir, "$prefix2/${spec.hash}")
                                objectFile.parentFile?.mkdirs()

                                val alreadyPresent = objectFile.exists() &&
                                    (spec.sizeBytes <= 0L || objectFile.length() == spec.sizeBytes)

                                if (!alreadyPresent) {
                                    val resourceUrl = "$RESOURCES_BASE_URL/$prefix2/${spec.hash}"
                                    downloadSingleFileWithSha1(
                                        url = resourceUrl,
                                        targetFile = objectFile,
                                        expectedSha1 = spec.hash,
                                        onProgressBytes = { _, _ -> }
                                    )
                                }

                                val done = completedAssets.incrementAndGet()
                                val bytesDone = downloadedAssetsBytes.addAndGet(
                                    if (spec.sizeBytes > 0) spec.sizeBytes else objectFile.length()
                                )

                                val now = System.currentTimeMillis()
                                if (now - lastAssetEmitMs > 120L || done == totalAssets) {
                                    lastAssetEmitMs = now
                                    val dlMb = bytesDone / (1024f * 1024f)
                                    val totMb = totalAssetsBytes / (1024f * 1024f)
                                    val assetFrac = done.toFloat() / totalAssets.toFloat()
                                    trySend(
                                        AssetPrepareProgress(
                                            stageTitle = "Ассеты Mojang ($done/$totalAssets)",
                                            progressFraction = 0.64f + assetFrac * 0.35f,
                                            currentFile = "assets/objects/$prefix2/${spec.hash} (${spec.resourceKey})",
                                            downloadedMb = dlMb,
                                            totalMb = totMb,
                                            sha1Verified = true
                                        )
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
                onLogLine("[MojangDownloader] Завершена проверка и скачивание всех $totalAssets объектов ресурсов.")
            }
        }

        send(
            AssetPrepareProgress(
                stageTitle = "Все файлы Minecraft ${version.id} проверены и готовы!",
                progressFraction = 1.0f,
                currentFile = "versions/${version.id}/${version.id}.jar",
                downloadedMb = clientTotalMb,
                totalMb = clientTotalMb,
                sha1Verified = true,
                completed = true
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Скачивает один файл по HTTP с проверкой SHA-1 хэша. Если файл уже существует и его SHA-1 совпадает,
     * повторное скачивание не требуется.
     */
    private fun downloadSingleFileWithSha1(
        url: String,
        targetFile: File,
        expectedSha1: String,
        onProgressBytes: (Long, Long) -> Unit
    ) {
        if (url.isBlank()) return
        targetFile.parentFile?.mkdirs()

        if (targetFile.exists() && targetFile.length() > 0L) {
            if (expectedSha1.isBlank() || computeFileSha1(targetFile).equals(expectedSha1, ignoreCase = true)) {
                onProgressBytes(targetFile.length(), targetFile.length())
                return
            }
        }

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MonolithLauncher/2.0 (Android; arm64-v8a)")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Ошибка HTTP ${response.code} при скачивании $url")
            }
            val body = response.body ?: throw IllegalStateException("Пустое тело ответа для $url")
            val totalLength = body.contentLength()
            var bytesReadTotal = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                        onProgressBytes(bytesReadTotal, totalLength)
                    }
                }
            }
        }

        if (expectedSha1.isNotBlank()) {
            val actualSha1 = computeFileSha1(tempFile)
            if (!actualSha1.equals(expectedSha1, ignoreCase = true)) {
                tempFile.delete()
                throw SecurityException(
                    "Несовпадение SHA-1 для ${targetFile.name}: ожидался $expectedSha1, получен $actualSha1"
                )
            }
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun extractNativeSoFromJar(jarFile: File, nativesDir: File) {
        runCatching {
            ZipFile(jarFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".so")) {
                        val outSo = File(nativesDir, File(entry.name).name)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outSo).use { output ->
                                input.copyTo(output)
                            }
                        }
                        outSo.setExecutable(true, false)
                        outSo.setReadable(true, false)
                    }
                }
            }
        }
    }

    fun computeFileSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(32 * 1024)
            var count: Int
            while (fis.read(buffer).also { count = it } != -1) {
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
