package com.example.pojavruntime

import android.app.ActivityManager
import android.content.Context
import android.os.Build

class AiCrashAndHardwareEngine {

    /**
     * AI Hardware Tuner: Анализирует ОЗУ смартфона, процессор и чипсет для подбора оптимальных
     * флагов JVM (-Xmx, G1GC / Generational ZGC) под 60/120 FPS.
     */
    fun inspectAndTuneHardware(
        context: Context,
        targetVersion: MinecraftVersion,
        targetFps: Int = 120
    ): HardwareProfile {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRamMb = if (memInfo.totalMem > 0) {
            (memInfo.totalMem / (1024 * 1024)).toInt()
        } else {
            6144
        }
        val availRamMb = if (memInfo.availMem > 0) {
            (memInfo.availMem / (1024 * 1024)).toInt()
        } else {
            3072
        }

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        // Safe heap calculation leaving headroom for GL4ES / ANGLE native textures & Android OS
        val recommendedHeapMb = when {
            targetVersion.era in listOf(
                VanillaEra.PRE_CLASSIC,
                VanillaEra.CLASSIC,
                VanillaEra.INFDEV,
                VanillaEra.ALPHA,
                VanillaEra.BETA
            ) -> 1024
            totalRamMb >= 11000 -> 4096
            totalRamMb >= 7500 -> 3072
            totalRamMb >= 5500 -> 2048
            else -> 1536
        }.coerceAtMost((availRamMb * 0.72).toInt().coerceAtLeast(1024))

        val recommendedGc = when {
            targetVersion.recommendedJava == JavaRuntimeMajor.JRE_21 && totalRamMb >= 7500 ->
                GcAlgorithm.ZGC
            totalRamMb < 4200 ->
                GcAlgorithm.PARALLEL
            else ->
                GcAlgorithm.G1GC
        }

        val recommendedRenderer = when {
            targetVersion.recommendedJava == JavaRuntimeMajor.JRE_8 ->
                MobileGlRenderer.GL4ES_114
            soc.contains("qcom", ignoreCase = true) ||
                soc.contains("sm8", ignoreCase = true) ||
                soc.contains("adreno", ignoreCase = true) ||
                totalRamMb >= 7500 ->
                MobileGlRenderer.ANGLE_VULKAN
            else ->
                MobileGlRenderer.GL4ES_114
        }

        val parallelThreads = (cores - 2).coerceIn(2, 6)
        val maxPauseMillis = if (targetFps >= 120) 25 else 45

        val flags = buildString {
            append("-XX:+UnlockExperimentalVMOptions ")
            append("-XX:+AlwaysPreTouch ")
            append("-XX:+DisableExplicitGC ")
            append("-XX:ParallelGCThreads=$parallelThreads ")
            if (recommendedGc == GcAlgorithm.G1GC) {
                append("-XX:MaxGCPauseMillis=$maxPauseMillis ")
                append("-XX:G1NewSizePercent=20 ")
                append("-XX:G1ReservePercent=20 ")
                append("-XX:G1HeapRegionSize=32M ")
                append("-XX:G1MixedGCCountTarget=4 ")
            }
            append("-Dfml.ignoreInvalidMinecraftCertificates=true")
        }

        return HardwareProfile(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            socName = soc,
            primaryAbi = primaryAbi,
            cpuCores = cores,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            recommendedHeapMb = recommendedHeapMb,
            recommendedRenderer = recommendedRenderer,
            recommendedGc = recommendedGc,
            generatedFlags = flags
        )
    }

    /**
     * AI Crash & Log Analyzer: Мгновенный локальный парсер latest.log и crash-reports
     * с генерацией 1-Click Fix действия.
     */
    fun analyzeLogOrCrash(
        logText: String,
        targetVersion: MinecraftVersion,
        currentConfig: JvmRuntimeConfig
    ): CrashDiagnosis {
        val lines = logText.lines()

        // 1. Check Java ClassFile Version mismatch (UnsupportedClassVersionError)
        val classVerLine = lines.firstOrNull {
            it.contains("UnsupportedClassVersionError", ignoreCase = true) ||
                it.contains("class file version", ignoreCase = true)
        }
        if (classVerLine != null) {
            val requiredJava = when {
                classVerLine.contains("65.0") -> JavaRuntimeMajor.JRE_21
                classVerLine.contains("61.0") -> JavaRuntimeMajor.JRE_17
                else -> targetVersion.recommendedJava
            }
            return CrashDiagnosis(
                errorCode = "JVM_CLASSFILE_MISMATCH",
                title = "Несовместимость версии OpenJDK с классом игры",
                rootCauseExplanation = "Версия Minecraft ${targetVersion.id} скомпилирована под ${requiredJava.title}, но была запущена на более старой виртуальной машине (${currentConfig.selectedJava.title}).",
                offendingLogLine = classVerLine.trim(),
                fixAction = OneClickFixAction.SwitchJavaRuntime(requiredJava)
            )
        }

        // 2. Check OutOfMemoryError (Java Heap Space vs Native GL Buffer OOM)
        val oomLine = lines.firstOrNull {
            it.contains("OutOfMemoryError", ignoreCase = true) ||
                it.contains("GL_OUT_OF_MEMORY", ignoreCase = true) ||
                it.contains("Failed to allocate", ignoreCase = true)
        }
        if (oomLine != null) {
            val isNativeOom = oomLine.contains("Direct buffer", ignoreCase = true) ||
                oomLine.contains("GL_OUT_OF_MEMORY", ignoreCase = true)
            val suggestedHeap = if (isNativeOom) {
                (currentConfig.allocatedMemoryMb - 512).coerceAtLeast(1280)
            } else {
                (currentConfig.allocatedMemoryMb + 768).coerceAtMost(4096)
            }
            val explanation = if (isNativeOom) {
                "Слишком большой размер Java Heap (-Xmx${currentConfig.allocatedMemoryMb}M) оставил недостаточно нативной памяти RAM для текстурных буферов GL4ES/LWJGL. Рекомендуется снизить -Xmx до ${suggestedHeap}M."
            } else {
                "Виртуальная машина исчерпала выделенный пул кучи Java (-Xmx${currentConfig.allocatedMemoryMb}M) при генерации чанков. Рекомендуется увеличить выделение до ${suggestedHeap}M."
            }
            return CrashDiagnosis(
                errorCode = if (isNativeOom) "NATIVE_GL_OOM" else "JVM_HEAP_OOM",
                title = if (isNativeOom) "Переполнение нативной памяти драйвера OpenGL" else "Нехватка памяти Java Heap (OutOfMemoryError)",
                rootCauseExplanation = explanation,
                offendingLogLine = oomLine.trim(),
                fixAction = OneClickFixAction.AdjustHeapMemory(suggestedHeap)
            )
        }

        // 3. Check GLSL / Shader / EGL context errors
        val glErrorLine = lines.firstOrNull {
            it.contains("GLFW_PLATFORM_ERROR", ignoreCase = true) ||
                it.contains("EGL_BAD_CONFIG", ignoreCase = true) ||
                it.contains("Shader compilation failed", ignoreCase = true) ||
                it.contains("glCompileShader", ignoreCase = true)
        }
        if (glErrorLine != null) {
            val fallbackRenderer = if (currentConfig.renderer == MobileGlRenderer.GL4ES_114) {
                MobileGlRenderer.ANGLE_VULKAN
            } else {
                MobileGlRenderer.GL4ES_114
            }
            return CrashDiagnosis(
                errorCode = "GL_RENDERER_SHADER_FAULT",
                title = "Ошибка трансляции шейдеров мобильного GPU (${currentConfig.renderer.title})",
                rootCauseExplanation = "Текущий графический бэкенд не смог скомпилировать GLSL-шейдер или инициализировать контекст EGL для версии ${targetVersion.id}. Переключение на ${fallbackRenderer.title} устранит сбой.",
                offendingLogLine = glErrorLine.trim(),
                fixAction = OneClickFixAction.SwitchGlRenderer(fallbackRenderer)
            )
        }

        // 4. Check SHA-1 / Asset / Library corruption
        val assetErrorLine = lines.firstOrNull {
            it.contains("ZipException", ignoreCase = true) ||
                it.contains("SHA-1 mismatch", ignoreCase = true) ||
                it.contains("ClassNotFoundException", ignoreCase = true) ||
                it.contains("UnsatisfiedLinkError", ignoreCase = true)
        }
        if (assetErrorLine != null) {
            return CrashDiagnosis(
                errorCode = "ASSET_HASH_CORRUPTION",
                title = "Нарушение целостности архива библиотек или нативных бинарников",
                rootCauseExplanation = "Обнаружен обрыв скачивания JAR-библиотеки или несовпадение контрольной суммы SHA-1. Повторная верификация манифеста восстановит недостающие файлы.",
                offendingLogLine = assetErrorLine.trim(),
                fixAction = OneClickFixAction.RepairAssetIntegrity
            )
        }

        // Default healthy / diagnostic summary
        return CrashDiagnosis(
            errorCode = "SESSION_HEALTHY_OR_GENERIC",
            title = "Критических исключений JVM в логе не обнаружено",
            rootCauseExplanation = "Конвейер запуска ${targetVersion.id} соответствует рекомендуемой конфигурации (${targetVersion.recommendedJava.title}, ${currentConfig.renderer.title}). Вы можете сгенерировать тестовый сбой для проверки авто-ремонта.",
            offendingLogLine = lines.lastOrNull { it.isNotBlank() } ?: "[PojavCore/INFO]: Ready.",
            fixAction = OneClickFixAction.SwitchJavaRuntime(targetVersion.recommendedJava)
        )
    }
}
