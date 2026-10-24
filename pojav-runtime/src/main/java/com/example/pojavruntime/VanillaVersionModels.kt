package com.example.pojavruntime

enum class VanillaEra(val displayName: String, val badgeCode: String) {
    RELEASE("Releases", "REL"),
    SNAPSHOT("Snapshots", "SNAP"),
    BETA("Old Beta", "BETA"),
    ALPHA("Old Alpha", "ALPHA"),
    INFDEV("Infdev", "INFD"),
    CLASSIC("Classic", "CLAS"),
    PRE_CLASSIC("Pre-Classic", "PRE")
}

enum class JavaRuntimeMajor(
    val major: Int,
    val title: String,
    val folderName: String,
    val description: String
) {
    JRE_8(
        major = 8,
        title = "OpenJDK 8 (aarch64)",
        folderName = "jre_8",
        description = "Для версий Minecraft до 1.16.5 (javaVersion.majorVersion = 8)"
    ),
    JRE_17(
        major = 17,
        title = "OpenJDK 17 (aarch64)",
        folderName = "jre_17",
        description = "Для Minecraft 1.17 – 1.20.4 (javaVersion.majorVersion = 17)"
    ),
    JRE_21(
        major = 21,
        title = "OpenJDK 21 (aarch64)",
        folderName = "jre_21",
        description = "Для Minecraft 1.20.5+ и снапшотов (javaVersion.majorVersion = 21)"
    );

    companion object {
        fun fromMajor(major: Int): JavaRuntimeMajor = when {
            major >= 21 -> JRE_21
            major >= 17 -> JRE_17
            else -> JRE_8
        }
    }
}

enum class MobileGlRenderer(
    val id: String,
    val title: String,
    val envRendererValue: String,
    val glVersion: String,
    val gpuTarget: String
) {
    GL4ES_114(
        id = "gl4es_114",
        title = "GL4ES 1.1.5 (Pojav Core)",
        envRendererValue = "opengles2",
        glVersion = "OpenGL 2.1 -> ES 2.0/3.0",
        gpuTarget = "Максимальная совместимость (Adreno / Mali / PowerVR)"
    ),
    ANGLE_VULKAN(
        id = "angle_vulkan",
        title = "ANGLE (OpenGL ES on Vulkan)",
        envRendererValue = "vulkan_angle",
        glVersion = "OpenGL 3.2 Core -> Vulkan 1.1+",
        gpuTarget = "Рекомендуется для 1.17+ на Adreno 600/700 и Xclipse"
    ),
    VIRGL_MESA(
        id = "virgl_mesa",
        title = "VirGL Mesa Gallium",
        envRendererValue = "gallium_virgl",
        glVersion = "OpenGL 3.3 Mesa",
        gpuTarget = "Программный/гибридный конвейер для сложных шейдеров"
    )
}

enum class GcAlgorithm(val title: String, val jvmFlag: String) {
    G1GC("G1GC (Aikar's Profile)", "-XX:+UseG1GC"),
    ZGC("Generational ZGC (Java 21+)", "-XX:+UseZGC -XX:+ZGenerational"),
    PARALLEL("ParallelOldGC (Low RAM / Legacy)", "-XX:+UseParallelGC")
}

data class MinecraftVersion(
    val id: String,
    val era: VanillaEra,
    val releaseTime: String,
    val manifestUrl: String,
    val sha1: String,
    val recommendedJava: JavaRuntimeMajor,
    val mainClass: String,
    val assetsIndex: String,
    val summary: String,
    val isVerifiedLocal: Boolean = false
)

data class AssetPrepareProgress(
    val stageTitle: String,
    val progressFraction: Float,
    val currentFile: String,
    val downloadedMb: Float = 0f,
    val totalMb: Float = 0f,
    val percentage: Int = (progressFraction * 100).toInt().coerceIn(0, 100),
    val sha1Verified: Boolean = false,
    val completed: Boolean = false
)

data class MojangComponentsStatus(
    val versionId: String = "",
    val versionJsonInstalled: Boolean = false,
    val clientJarInstalled: Boolean = false,
    val clientJarSizeMb: Float = 0f,
    val assetIndexInstalled: Boolean = false,
    val assetObjectsInstalledCount: Int = 0,
    val mojangLibrariesInstalledCount: Int = 0,
    val jreInstalled: Boolean = false,
    val resolvedJavaMajor: Int = 21
) {
    val isFullyReady: Boolean
        get() = versionJsonInstalled && clientJarInstalled && assetIndexInstalled &&
            mojangLibrariesInstalledCount > 0
}

data class JvmRuntimeConfig(
    val autoSelectJava: Boolean = true,
    val selectedJava: JavaRuntimeMajor = JavaRuntimeMajor.JRE_21,
    val renderer: MobileGlRenderer = MobileGlRenderer.GL4ES_114,
    val allocatedMemoryMb: Int = 2048,
    val gcAlgorithm: GcAlgorithm = GcAlgorithm.G1GC,
    val targetFps: Int = 120,
    val resolutionScalePercent: Int = 100,
    val customJvmFlags: String = "-XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M"
)

data class HardwareProfile(
    val deviceModel: String,
    val socName: String,
    val primaryAbi: String,
    val cpuCores: Int,
    val totalRamMb: Int,
    val availableRamMb: Int,
    val recommendedHeapMb: Int,
    val recommendedRenderer: MobileGlRenderer,
    val recommendedGc: GcAlgorithm,
    val generatedFlags: String
)

sealed class OneClickFixAction(val buttonLabel: String) {
    data class SwitchJavaRuntime(val targetJava: JavaRuntimeMajor) :
        OneClickFixAction("Переключить рантайм на ${targetJava.title}")

    data class AdjustHeapMemory(val targetHeapMb: Int) :
        OneClickFixAction("Установить выделение ОЗУ -Xmx${targetHeapMb}M")

    data class SwitchGlRenderer(val targetRenderer: MobileGlRenderer) :
        OneClickFixAction("Переключить рендерер на ${targetRenderer.title}")

    data object RepairAssetIntegrity :
        OneClickFixAction("Перекачать поврежденные ассеты и проверить SHA-1")
}

data class CrashDiagnosis(
    val errorCode: String,
    val title: String,
    val rootCauseExplanation: String,
    val offendingLogLine: String,
    val fixAction: OneClickFixAction,
    val aiEnhancedNotes: String? = null
)
