package com.example

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiHybridAssistant
import com.example.auth.AuthAccountManager
import com.example.auth.MinecraftAccount
import com.example.controller.ControllerProfileStorage
import com.example.controller.GlfwKeyBinding
import com.example.controller.VirtualControlNode
import com.example.data.repository.CloudLaunchDatabaseRepository
import com.example.data.repository.DiagnosticReportRecord
import com.example.data.repository.LaunchProfileRecord
import com.example.pojavruntime.AiCrashAndHardwareEngine
import com.example.pojavruntime.AssetPrepareProgress
import com.example.pojavruntime.CallbackBridge
import com.example.pojavruntime.CrashDiagnosis
import com.example.pojavruntime.GcAlgorithm
import com.example.pojavruntime.HardwareProfile
import com.example.pojavruntime.JavaRuntimeMajor
import com.example.pojavruntime.JavaRuntimeManager
import com.example.pojavruntime.JvmRuntimeConfig
import com.example.pojavruntime.MinecraftLauncherCore
import com.example.pojavruntime.MinecraftVersion
import com.example.pojavruntime.MobileGlRenderer
import com.example.pojavruntime.MojangComponentsStatus
import com.example.pojavruntime.MojangDownloader
import com.example.pojavruntime.OneClickFixAction
import com.example.pojavruntime.PojavLaunchSpec
import com.example.pojavruntime.PojavLogger
import com.example.pojavruntime.PojavNativeBridge
import com.example.pojavruntime.PreLaunchDiagnosticInspector
import com.example.pojavruntime.PreLaunchInspectionResult
import com.example.pojavruntime.VanillaEra
import com.example.skineditor.MojangAndElySkinClient
import com.example.skineditor.ProceduralAiSkinSynthesizer
import com.example.skineditor.SkinFormat
import com.example.skineditor.SkinProviderSource
import com.example.skineditor.SkinTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class LauncherDestination(val title: String) {
    PLAY_HUB("Запуск"),
    GAME_SESSION("Сессия JVM"),
    TOUCH_CONTROLLER("Контроллер"),
    SKIN_STUDIO("Скины 2D/3D"),
    ACCOUNTS_AUTH("Аккаунты"),
    AI_ENGINEER("AI Инженер"),
    RUNTIME_SETTINGS("Среда JRE")
}

data class LauncherUiState(
    val currentDestination: LauncherDestination = LauncherDestination.PLAY_HUB,
    val splashQuote: String = "100% Pure Vanilla!",
    val allSplashQuotes: List<String> = emptyList(),
    val versions: List<MinecraftVersion> = emptyList(),
    val selectedEraFilter: VanillaEra? = null,
    val versionSearchQuery: String = "",
    val selectedVersion: MinecraftVersion? = null,
    val isFetchingManifest: Boolean = false,
    val manifestErrorMessage: String? = null,
    val prepareProgress: AssetPrepareProgress? = null,
    val mojangComponentsStatus: MojangComponentsStatus = MojangComponentsStatus(),
    val runtimeConfig: JvmRuntimeConfig = JvmRuntimeConfig(),
    val accounts: List<MinecraftAccount> = emptyList(),
    val authStatusMessage: String = "",
    val controllerNodes: List<VirtualControlNode> = emptyList(),
    val selectedControllerNodeId: String = "stick_wasd",
    val controllerEditMode: Boolean = true,
    val controllerSnapToGrid: Boolean = true,
    val lastPressedGlfwKey: String = "IDLE",
    val isVirtualMouseEnabled: Boolean = false,
    val virtualMouseX: Float = 0.5f,
    val virtualMouseY: Float = 0.5f,
    val analogVectorX: Float = 0f,
    val analogVectorY: Float = 0f,
    val cameraYaw: Float = 0f,
    val cameraPitch: Float = 0f,
    val surfaceWidth: Int = 1920,
    val surfaceHeight: Int = 1080,
    val isSurfaceBound: Boolean = false,
    val showPauseMenuModal: Boolean = false,
    val gameLogs: List<String> = emptyList(),
    val isGameSessionRunning: Boolean = false,
    val showSessionDebugF3: Boolean = false,
    val showSessionConsoleDrawer: Boolean = true,
    val skinFormat: SkinFormat = SkinFormat.MODERN_64X64,
    val skinPixels: IntArray = IntArray(64 * 64),
    val skinTool: SkinTool = SkinTool.PENCIL,
    val selectedColorArgb: Int = 0xFF38C76A.toInt(),
    val showSkinOverlay: Boolean = true,
    val showSkinGrid: Boolean = true,
    val showSkinZoneGuides: Boolean = true,
    val skinPreviewYaw: Float = 25f,
    val skinPreviewPitch: Float = 0f,
    val skinStatusBanner: String = "Готов к редактированию 64×64 (Overlay включен)",
    val hardwareProfile: HardwareProfile? = null,
    val crashDiagnosis: CrashDiagnosis? = null,
    val preLaunchInspection: PreLaunchInspectionResult? = null,
    val isRunningPreLaunchCheck: Boolean = false,
    val cloudLaunchProfiles: List<LaunchProfileRecord> = emptyList(),
    val cloudDiagnosticReports: List<DiagnosticReportRecord> = emptyList(),
    val cloudSyncStatus: String = "Подключено к облачной БД Firestore",
    val isAiAnalyzing: Boolean = false,
    val useDynamicMaterialYou: Boolean = false,
    val isGeminiKeyActive: Boolean = false
)

class LauncherViewModel(
    context: Context,
    private val cloudRepository: CloudLaunchDatabaseRepository,
    val currentUserId: String
) : ViewModel() {

    private val appContext: Context = context.applicationContext
    val minecraftRootDir: File =
        appContext.getExternalFilesDir("minecraft") ?: File(appContext.filesDir, "minecraft").apply { mkdirs() }

    private val mojangDownloader = MojangDownloader()
    private val javaRuntimeManager = JavaRuntimeManager()
    private val launcherCore = MinecraftLauncherCore(mojangDownloader, javaRuntimeManager)
    private val preLaunchInspector = PreLaunchDiagnosticInspector(appContext, minecraftRootDir, appContext.filesDir)
    private val aiEngine = AiCrashAndHardwareEngine()
    private val authManager = AuthAccountManager()
    private val skinClient = MojangAndElySkinClient()
    private val geminiAssistant = GeminiHybridAssistant()

    @Volatile
    private var activeRenderSurface: Surface? = null

    private val skinUndoStack = ArrayDeque<IntArray>()

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    init {
        val splashes = loadSplashesFromAssets()
        val initialAccounts = authManager.loadSavedAccounts(appContext.filesDir)
        val initialController = ControllerProfileStorage.loadLayout(appContext.filesDir)
        val initialSkin = ProceduralAiSkinSynthesizer.generateFromPrompt(
            prompt = "Monolith Emerald Knight Visor",
            format = SkinFormat.MODERN_64X64
        )
        val placeholderVer = MinecraftVersion(
            id = "1.21.4",
            era = VanillaEra.RELEASE,
            releaseTime = "2024-12-03",
            manifestUrl = "",
            sha1 = "",
            recommendedJava = JavaRuntimeMajor.JRE_21,
            mainClass = "net.minecraft.client.main.Main",
            assetsIndex = "1.21",
            summary = "Загрузка официального манифеста Mojang..."
        )
        val hwProfile = aiEngine.inspectAndTuneHardware(appContext, placeholderVer, 120)

        _uiState.update {
            it.copy(
                splashQuote = splashes.randomOrNull() ?: "100% Pure Vanilla!",
                allSplashQuotes = splashes,
                accounts = initialAccounts,
                controllerNodes = initialController,
                skinPixels = initialSkin,
                hardwareProfile = hwProfile,
                gameLogs = emptyList(),
                runtimeConfig = it.runtimeConfig.copy(
                    allocatedMemoryMb = hwProfile.recommendedHeapMb,
                    renderer = hwProfile.recommendedRenderer,
                    gcAlgorithm = hwProfile.recommendedGc,
                    customJvmFlags = hwProfile.generatedFlags
                ),
                isGeminiKeyActive = geminiAssistant.isApiKeyConfigured()
            )
        }

        startObservingLatestLogFile()
        refreshMojangManifest()
        startObservingCloudDatabase()
        runPreLaunchDiagnosticAndRepair(saveToCloud = false)
    }

    private fun startObservingLatestLogFile() {
        val latestLogFile = File(minecraftRootDir, "latestlog.txt")
        viewModelScope.launch {
            PojavLogger.observeLatestLogFile(latestLogFile).collect { realLine ->
                _uiState.update { cur ->
                    val currentLogs = cur.gameLogs
                    if (currentLogs.lastOrNull() == realLine) {
                        cur
                    } else {
                        cur.copy(gameLogs = (currentLogs + realLine).takeLast(300))
                    }
                }
            }
        }
    }

    private fun startObservingCloudDatabase() {
        if (currentUserId == "local_offline_user") {
            _uiState.update {
                it.copy(cloudSyncStatus = "Локальный режим профилей • Войдите через Google во вкладке Аккаунты для облачной БД")
            }
            return
        }
        viewModelScope.launch {
            cloudRepository.observeLaunchProfiles()
                .catch { err ->
                    Log.w("LauncherVM", "Error observing launch_profiles", err)
                    _uiState.update {
                        it.copy(cloudSyncStatus = "Локальный кэш профилей активен")
                    }
                }
                .collect { profiles ->
                    _uiState.update {
                        it.copy(
                            cloudLaunchProfiles = profiles,
                            cloudSyncStatus = "Облачная БД Firestore активна • Профилей: ${profiles.size}"
                        )
                    }
                }
        }
        viewModelScope.launch {
            cloudRepository.observeDiagnosticReports()
                .catch { err ->
                    Log.w("LauncherVM", "Error observing diagnostic_reports", err)
                }
                .collect { reports ->
                    _uiState.update {
                        it.copy(cloudDiagnosticReports = reports)
                    }
                }
        }
    }

    private fun loadSplashesFromAssets(): List<String> {
        return runCatching {
            appContext.assets.open("splashes.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.getOrElse {
            listOf("100% Pure Vanilla!", "GL4ES & ANGLE Accelerated!", "OpenJDK 8, 17 & 21!")
        }
    }

    private fun appendLogLine(line: String) {
        val latestLogFile = File(minecraftRootDir, "latestlog.txt")
        PojavLogger.appendToLog(latestLogFile, line)
    }

    fun cycleSplashQuote() {
        val list = _uiState.value.allSplashQuotes
        if (list.isNotEmpty()) {
            _uiState.update { it.copy(splashQuote = list.random()) }
        }
    }

    fun navigateTo(destination: LauncherDestination) {
        _uiState.update {
            it.copy(
                currentDestination = destination,
                isGameSessionRunning = destination == LauncherDestination.GAME_SESSION,
                showPauseMenuModal = false
            )
        }
    }

    fun exitFullscreenGameToLauncher() {
        launcherCore.terminateActiveSession()
        val ver = _uiState.value.selectedVersion
        val updatedStatus = mojangDownloader.inspectInstalledComponents(ver, minecraftRootDir, appContext.filesDir)
        _uiState.update {
            it.copy(
                currentDestination = LauncherDestination.PLAY_HUB,
                isGameSessionRunning = false,
                showPauseMenuModal = false,
                mojangComponentsStatus = updatedStatus
            )
        }
    }

    fun toggleDynamicMaterialYou(enabled: Boolean) {
        _uiState.update { it.copy(useDynamicMaterialYou = enabled) }
    }

    // --- 1. Реальный загрузчик Mojang (MojangDownloader.kt) ---

    fun refreshMojangManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingManifest = true, manifestErrorMessage = null) }
            appendLogLine("[MojangDownloader]: Запрос манифеста ${MojangDownloader.VERSION_MANIFEST_V2_URL}...")

            val result = mojangDownloader.fetchOfficialVersionManifest(minecraftRootDir)
            result.onSuccess { loaded ->
                val currentId = _uiState.value.selectedVersion?.id
                val chosen = loaded.firstOrNull { it.id == currentId } ?: loaded.firstOrNull()
                val compStatus = mojangDownloader.inspectInstalledComponents(
                    chosen,
                    minecraftRootDir,
                    appContext.filesDir
                )
                appendLogLine("[MojangDownloader]: Получено ${loaded.size} официальных версий Mojang.")
                _uiState.update { state ->
                    state.copy(
                        versions = loaded,
                        selectedVersion = chosen,
                        mojangComponentsStatus = compStatus,
                        isFetchingManifest = false,
                        manifestErrorMessage = null
                    )
                }
            }.onFailure { err ->
                appendLogLine("[MojangDownloader/ERROR]: Не удалось загрузить манифест: ${err.message}")
                _uiState.update { state ->
                    state.copy(
                        isFetchingManifest = false,
                        manifestErrorMessage = err.message ?: "Ошибка сети при обращении к piston-meta.mojang.com"
                    )
                }
            }
        }
    }

    fun selectEraFilter(era: VanillaEra?) {
        _uiState.update { it.copy(selectedEraFilter = era) }
    }

    fun updateVersionSearchQuery(query: String) {
        _uiState.update { it.copy(versionSearchQuery = query) }
    }

    fun selectVersion(version: MinecraftVersion) {
        val hw = aiEngine.inspectAndTuneHardware(
            context = appContext,
            targetVersion = version,
            targetFps = _uiState.value.runtimeConfig.targetFps
        )
        val compStatus = mojangDownloader.inspectInstalledComponents(
            version,
            minecraftRootDir,
            appContext.filesDir
        )
        _uiState.update { state ->
            val updatedRuntime = if (state.runtimeConfig.autoSelectJava) {
                state.runtimeConfig.copy(selectedJava = JavaRuntimeMajor.fromMajor(compStatus.resolvedJavaMajor))
            } else {
                state.runtimeConfig
            }
            state.copy(
                selectedVersion = version,
                mojangComponentsStatus = compStatus,
                runtimeConfig = updatedRuntime,
                hardwareProfile = hw
            )
        }
    }

    /**
     * Скачивает все официальные файлы выбранной версии Mojang + портативную OpenJDK (aarch64)
     * с выводом реального прогресса и логов.
     */
    fun installMojangComponentsOnly() {
        val version = _uiState.value.selectedVersion ?: return
        viewModelScope.launch {
            mojangDownloader.downloadVersionComplete(
                version = version,
                minecraftRootDir = minecraftRootDir,
                onLogLine = ::appendLogLine
            ).catch { err ->
                appendLogLine("[MojangDownloader/ERROR]: ${err.message}")
                _uiState.update { it.copy(prepareProgress = null) }
            }.collect { progress ->
                _uiState.update { it.copy(prepareProgress = progress) }
            }

            // Проверяем/скачиваем требуемый рантайм OpenJDK (aarch64)
            val versionJsonFile = File(minecraftRootDir, "versions/${version.id}/${version.id}.json")
            val requiredJava = javaRuntimeManager.resolveRequiredJavaFromVersionJson(versionJsonFile, version)

            javaRuntimeManager.ensureJavaRuntimeInstalled(
                filesDir = appContext.filesDir,
                javaMajor = requiredJava,
                onLogLine = ::appendLogLine
            ).catch { err ->
                appendLogLine("[JavaRuntimeManager/ERROR]: ${err.message}")
            }.collect { jreProgress ->
                _uiState.update { it.copy(prepareProgress = jreProgress) }
            }

            val updatedStatus = mojangDownloader.inspectInstalledComponents(
                version,
                minecraftRootDir,
                appContext.filesDir
            )
            _uiState.update {
                it.copy(
                    prepareProgress = null,
                    mojangComponentsStatus = updatedStatus
                )
            }
        }
    }

    // --- 2. Полный цикл скачивания и нативного запуска Minecraft: Java Edition ---

    fun launchSelectedVanillaVersion() {
        val state = _uiState.value
        val version = state.selectedVersion ?: return
        val activeAccount = state.accounts.firstOrNull { it.isActive }
            ?: state.accounts.firstOrNull()
            ?: authManager.createOfflineAccount("Player")

        viewModelScope.launch {
            var downloadFailed = false

            // 1. Скачивание <id>.json, client.jar (SHA-1), всех Maven-библиотек и всех ассетов (Semaphore(16))
            mojangDownloader.downloadVersionComplete(
                version = version,
                minecraftRootDir = minecraftRootDir,
                onLogLine = ::appendLogLine
            ).catch { err ->
                downloadFailed = true
                appendLogLine("[MojangDownloader/FATAL]: Сбой загрузки файлов Mojang: ${err.message}")
                _uiState.update { it.copy(prepareProgress = null) }
            }.collect { progress ->
                _uiState.update { it.copy(prepareProgress = progress) }
            }

            if (downloadFailed) return@launch

            // 2. Определение javaVersion.majorVersion из скачанного JSON и установка OpenJDK (aarch64)
            val versionJsonFile = File(minecraftRootDir, "versions/${version.id}/${version.id}.json")
            val requiredJava = if (_uiState.value.runtimeConfig.autoSelectJava) {
                javaRuntimeManager.resolveRequiredJavaFromVersionJson(versionJsonFile, version)
            } else {
                _uiState.value.runtimeConfig.selectedJava
            }

            javaRuntimeManager.ensureJavaRuntimeInstalled(
                filesDir = appContext.filesDir,
                javaMajor = requiredJava,
                onLogLine = ::appendLogLine
            ).catch { err ->
                appendLogLine("[JavaRuntimeManager/WARN]: ${err.message}")
            }.collect { jreProgress ->
                _uiState.update { it.copy(prepareProgress = jreProgress) }
            }

            val updatedStatus = mojangDownloader.inspectInstalledComponents(
                version,
                minecraftRootDir,
                appContext.filesDir
            )

            // 2.5. Автоматическая предпусковая диагностика и устранение 8 причин сбоя запуска
            val inspection = preLaunchInspector.inspectAndRepairBeforeLaunch(
                versionId = version.id,
                javaMajor = requiredJava.major,
                config = _uiState.value.runtimeConfig.copy(selectedJava = requiredJava),
                onLog = ::appendLogLine
            )
            cloudRepository.saveDiagnosticReport(
                versionId = version.id,
                javaMajor = requiredJava.major,
                totalChecks = inspection.checks.size,
                repairedIssuesCount = inspection.repairedIssuesCount,
                summary = "Авто-проверка перед запуском ${version.id}: исправлено ${inspection.repairedIssuesCount} узлов",
                status = inspection.overallStatus
            )

            // 3. Переход на экран с TextureView + Touch Overlay и запуск виртуальной машины Java
            _uiState.update {
                it.copy(
                    prepareProgress = null,
                    mojangComponentsStatus = updatedStatus,
                    preLaunchInspection = inspection,
                    runtimeConfig = it.runtimeConfig.copy(
                        selectedJava = requiredJava,
                        allocatedMemoryMb = inspection.recommendedHeapMb
                    ),
                    currentDestination = LauncherDestination.GAME_SESSION,
                    isGameSessionRunning = true,
                    showPauseMenuModal = false,
                    showSessionConsoleDrawer = false
                )
            }

            launcherCore.launchMinecraftJvmSession(
                filesDir = appContext.filesDir,
                minecraftRootDir = minecraftRootDir,
                version = version,
                runtimeConfig = _uiState.value.runtimeConfig,
                username = activeAccount.username,
                uuid = activeAccount.uuid,
                accessToken = activeAccount.accessToken,
                renderSurface = activeRenderSurface
            ).collect { logLine ->
                _uiState.update { cur ->
                    val nextLogs = (cur.gameLogs + logLine).takeLast(200)
                    val diagnosis = aiEngine.analyzeLogOrCrash(
                        logText = nextLogs.joinToString("\n"),
                        targetVersion = version,
                        currentConfig = cur.runtimeConfig
                    )
                    cur.copy(
                        gameLogs = nextLogs,
                        crashDiagnosis = diagnosis
                    )
                }
            }
        }
    }

    /**
     * Привязывает нативный Surface из TextureView к JNI-мосту CallbackBridge.setupBridgeWindow(surface).
     */
    fun onTextureViewSurfaceReady(surface: Surface, width: Int, height: Int) {
        activeRenderSurface = surface
        CallbackBridge.setupBridgeWindow(surface)
        CallbackBridge.sendUpdateWindowSize(width, height)
        _uiState.update {
            it.copy(
                surfaceWidth = width,
                surfaceHeight = height,
                isSurfaceBound = true
            )
        }
    }

    fun onTextureViewSurfaceSizeChanged(width: Int, height: Int) {
        CallbackBridge.sendUpdateWindowSize(width, height)
        _uiState.update {
            it.copy(
                surfaceWidth = width,
                surfaceHeight = height
            )
        }
    }

    fun onTextureViewSurfaceDestroyed() {
        activeRenderSurface = null
        CallbackBridge.setupBridgeWindow(null)
        _uiState.update { it.copy(isSurfaceBound = false) }
    }

    fun getCurrentLaunchSpecPreview(): PojavLaunchSpec? {
        val state = _uiState.value
        val version = state.selectedVersion ?: return null
        val acc = state.accounts.firstOrNull { it.isActive } ?: state.accounts.firstOrNull()
        return launcherCore.buildLaunchSpec(
            filesDir = appContext.filesDir,
            minecraftRootDir = minecraftRootDir,
            version = version,
            runtimeConfig = state.runtimeConfig,
            username = acc?.username ?: "Steve",
            uuid = acc?.uuid ?: "00000000-0000-0000-0000-000000000000",
            accessToken = acc?.accessToken ?: "0"
        )
    }

    fun toggleSessionDebugF3() {
        _uiState.update { it.copy(showSessionDebugF3 = !it.showSessionDebugF3) }
    }

    fun toggleSessionConsoleDrawer() {
        _uiState.update { it.copy(showSessionConsoleDrawer = !it.showSessionConsoleDrawer) }
    }

    fun togglePauseMenuModal(show: Boolean) {
        _uiState.update {
            it.copy(
                showPauseMenuModal = show,
                isVirtualMouseEnabled = if (show) true else it.isVirtualMouseEnabled
            )
        }
    }

    fun toggleVirtualMouseCursor() {
        _uiState.update { state ->
            val next = !state.isVirtualMouseEnabled
            state.copy(isVirtualMouseEnabled = next)
        }
    }

    fun onVirtualMouseDelta(dxNorm: Float, dyNorm: Float) {
        val state = _uiState.value
        val nextX = (state.virtualMouseX + dxNorm * 1.15f).coerceIn(0.0f, 1.0f)
        val nextY = (state.virtualMouseY + dyNorm * 1.15f).coerceIn(0.0f, 1.0f)
        PojavNativeBridge.dispatchCursorPosSafely(
            nextX * state.surfaceWidth,
            nextY * state.surfaceHeight
        )
        _uiState.update {
            it.copy(
                virtualMouseX = nextX,
                virtualMouseY = nextY
            )
        }
    }

    fun onVirtualMouseClick() {
        PojavNativeBridge.dispatchMouseButtonSafely(0, true)
        PojavNativeBridge.dispatchMouseButtonSafely(0, false)
        _uiState.update {
            it.copy(lastPressedGlfwKey = "GLFW_MOUSE_BUTTON_LEFT [CLICK]")
        }
    }

    // --- 3. Touch Controller & Нативная передача GLFW-кодов через JNI ---

    fun onGlfwKeyEvent(binding: GlfwKeyBinding, pressed: Boolean) {
        if (pressed) {
            when (binding) {
                GlfwKeyBinding.KEY_MOUSE_CURSOR -> {
                    toggleVirtualMouseCursor()
                }
                GlfwKeyBinding.MOUSE_BREAK -> {
                    PojavNativeBridge.dispatchMouseButtonSafely(0, true)
                }
                GlfwKeyBinding.MOUSE_PLACE -> {
                    PojavNativeBridge.dispatchMouseButtonSafely(1, true)
                }
                GlfwKeyBinding.KEY_PAUSE_ESC -> {
                    PojavNativeBridge.dispatchKeySafely(binding.glfwCode, true)
                    _uiState.update { it.copy(showPauseMenuModal = !it.showPauseMenuModal) }
                }
                GlfwKeyBinding.KEY_DEBUG_F3 -> {
                    PojavNativeBridge.dispatchKeySafely(binding.glfwCode, true)
                    _uiState.update { it.copy(showSessionDebugF3 = !it.showSessionDebugF3) }
                }
                GlfwKeyBinding.KEY_CHAT -> {
                    PojavNativeBridge.dispatchKeySafely(binding.glfwCode, true)
                    _uiState.update { it.copy(showSessionConsoleDrawer = !it.showSessionConsoleDrawer) }
                }
                else -> {
                    PojavNativeBridge.dispatchKeySafely(binding.glfwCode, true)
                }
            }
        } else {
            when (binding) {
                GlfwKeyBinding.MOUSE_BREAK -> PojavNativeBridge.dispatchMouseButtonSafely(0, false)
                GlfwKeyBinding.MOUSE_PLACE -> PojavNativeBridge.dispatchMouseButtonSafely(1, false)
                GlfwKeyBinding.KEY_MOUSE_CURSOR -> {}
                else -> PojavNativeBridge.dispatchKeySafely(binding.glfwCode, false)
            }
        }

        _uiState.update { state ->
            state.copy(
                lastPressedGlfwKey = if (pressed) "${binding.keyName} [DOWN]" else "${binding.keyName} [UP]"
            )
        }
    }

    fun onAnalogMove(vx: Float, vy: Float) {
        // Проброс осей WASD в нативный мост GLFW
        PojavNativeBridge.dispatchKeySafely(87, vy > 0.35f)  // GLFW_KEY_W
        PojavNativeBridge.dispatchKeySafely(83, vy < -0.35f) // GLFW_KEY_S
        PojavNativeBridge.dispatchKeySafely(65, vx < -0.35f) // GLFW_KEY_A
        PojavNativeBridge.dispatchKeySafely(68, vx > 0.35f)  // GLFW_KEY_D
        _uiState.update {
            it.copy(
                analogVectorX = vx,
                analogVectorY = vy
            )
        }
    }

    fun onCameraLookDelta(dx: Float, dy: Float) {
        val state = _uiState.value
        val nextYaw = (state.cameraYaw + dx * 0.35f) % 360f
        val nextPitch = (state.cameraPitch - dy * 0.35f).coerceIn(-90f, 90f)
        PojavNativeBridge.dispatchCursorPosSafely(
            state.surfaceWidth / 2f + dx,
            state.surfaceHeight / 2f + dy
        )
        _uiState.update {
            it.copy(
                cameraYaw = nextYaw,
                cameraPitch = nextPitch
            )
        }
    }

    fun setControllerEditMode(enabled: Boolean) {
        _uiState.update { it.copy(controllerEditMode = enabled) }
    }

    fun setControllerSnapToGrid(enabled: Boolean) {
        _uiState.update { it.copy(controllerSnapToGrid = enabled) }
    }

    fun selectControllerNode(nodeId: String) {
        _uiState.update { it.copy(selectedControllerNodeId = nodeId) }
    }

    fun updateControllerNodePosition(nodeId: String, normX: Float, normY: Float) {
        _uiState.update { state ->
            val updated = state.controllerNodes.map { node ->
                if (node.id == nodeId) {
                    node.copy(normalizedX = normX, normalizedY = normY)
                } else {
                    node
                }
            }
            ControllerProfileStorage.saveLayout(appContext.filesDir, updated)
            state.copy(controllerNodes = updated, selectedControllerNodeId = nodeId)
        }
    }

    fun updateSelectedControllerScale(newScale: Float) {
        val targetId = _uiState.value.selectedControllerNodeId
        _uiState.update { state ->
            val updated = state.controllerNodes.map { node ->
                if (node.id == targetId) node.copy(scale = newScale.coerceIn(0.5f, 1.8f)) else node
            }
            ControllerProfileStorage.saveLayout(appContext.filesDir, updated)
            state.copy(controllerNodes = updated)
        }
    }

    fun updateSelectedControllerOpacity(newOpacity: Float) {
        val targetId = _uiState.value.selectedControllerNodeId
        _uiState.update { state ->
            val updated = state.controllerNodes.map { node ->
                if (node.id == targetId) node.copy(opacity = newOpacity.coerceIn(0.15f, 1.0f)) else node
            }
            ControllerProfileStorage.saveLayout(appContext.filesDir, updated)
            state.copy(controllerNodes = updated)
        }
    }

    fun toggleSelectedControllerSticky(enabled: Boolean) {
        val targetId = _uiState.value.selectedControllerNodeId
        _uiState.update { state ->
            val updated = state.controllerNodes.map { node ->
                if (node.id == targetId) node.copy(isStickyToggle = enabled) else node
            }
            ControllerProfileStorage.saveLayout(appContext.filesDir, updated)
            state.copy(controllerNodes = updated)
        }
    }

    fun resetControllerLayoutToDefault() {
        val defaults = ControllerProfileStorage.defaultLandscapeLayout()
        ControllerProfileStorage.saveLayout(appContext.filesDir, defaults)
        _uiState.update {
            it.copy(
                controllerNodes = defaults,
                selectedControllerNodeId = defaults.first().id
            )
        }
    }

    // --- 4. Skin Studio (Mojang + Ely.by + 2D/3D + AI Skin Generator) ---

    fun selectSkinTool(tool: SkinTool) {
        _uiState.update { it.copy(skinTool = tool) }
    }

    fun selectSkinColor(argb: Int) {
        _uiState.update { it.copy(selectedColorArgb = argb, skinTool = SkinTool.PENCIL) }
    }

    fun toggleSkinOverlayLayer() {
        _uiState.update { it.copy(showSkinOverlay = !it.showSkinOverlay) }
    }

    fun switchSkinFormat(format: SkinFormat) {
        val current = _uiState.value
        if (current.skinFormat == format) return
        val newPixels = IntArray(format.width * format.height)
        val copyLen = minOf(current.skinPixels.size, newPixels.size)
        System.arraycopy(current.skinPixels, 0, newPixels, 0, copyLen)
        _uiState.update {
            it.copy(
                skinFormat = format,
                skinPixels = newPixels,
                skinStatusBanner = "Формат развертки переключен на ${format.label}"
            )
        }
    }

    fun rotateSkinPreview3D(deltaYaw: Float, deltaPitch: Float) {
        _uiState.update {
            it.copy(
                skinPreviewYaw = (it.skinPreviewYaw + deltaYaw) % 360f,
                skinPreviewPitch = (it.skinPreviewPitch + deltaPitch).coerceIn(-45f, 45f)
            )
        }
    }

    fun applyPixelOnSkinCanvas(px: Int, py: Int) {
        val state = _uiState.value
        val width = state.skinFormat.width
        val height = state.skinFormat.height
        val index = py * width + px
        if (index !in state.skinPixels.indices) return

        when (state.skinTool) {
            SkinTool.EYEDROPPER -> {
                val sampled = state.skinPixels[index]
                if (((sampled ushr 24) and 0xFF) > 0) {
                    _uiState.update {
                        it.copy(
                            selectedColorArgb = sampled,
                            skinTool = SkinTool.PENCIL,
                            skinStatusBanner = "Пипетка: выбран цвет #${Integer.toHexString(sampled).uppercase().takeLast(6)}"
                        )
                    }
                }
            }
            SkinTool.PENCIL -> {
                pushUndoSnapshot(state.skinPixels)
                val next = state.skinPixels.clone()
                next[index] = state.selectedColorArgb
                _uiState.update { it.copy(skinPixels = next) }
            }
            SkinTool.ERASER -> {
                pushUndoSnapshot(state.skinPixels)
                val next = state.skinPixels.clone()
                next[index] = 0x00000000
                _uiState.update { it.copy(skinPixels = next) }
            }
            SkinTool.FILL -> {
                pushUndoSnapshot(state.skinPixels)
                val next = state.skinPixels.clone()
                val zoneStartX = (px / 8) * 8
                val zoneStartY = (py / 8) * 8
                for (y in zoneStartY until (zoneStartY + 8).coerceAtMost(height)) {
                    for (x in zoneStartX until (zoneStartX + 8).coerceAtMost(width)) {
                        next[y * width + x] = state.selectedColorArgb
                    }
                }
                _uiState.update {
                    it.copy(
                        skinPixels = next,
                        skinStatusBanner = "Залита UV-секция 8×8 ($zoneStartX,$zoneStartY)"
                    )
                }
            }
        }
    }

    private fun pushUndoSnapshot(pixels: IntArray) {
        if (skinUndoStack.size >= 25) {
            skinUndoStack.removeFirst()
        }
        skinUndoStack.addLast(pixels.clone())
    }

    fun undoSkinEdit() {
        val previous = skinUndoStack.removeLastOrNull() ?: return
        _uiState.update {
            it.copy(
                skinPixels = previous,
                skinStatusBanner = "Отменено последнее действие на холсте"
            )
        }
    }

    fun fetchSkinFromProvider(username: String, provider: SkinProviderSource) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(skinStatusBanner = "Запрос текстуры $username через ${provider.label}...")
            }
            val res = skinClient.fetchPlayerSkin(username, provider)
            pushUndoSnapshot(_uiState.value.skinPixels)
            _uiState.update {
                it.copy(
                    skinFormat = res.format,
                    skinPixels = res.skinBitmapPixels,
                    skinStatusBanner = res.statusMessage
                )
            }
        }
    }

    fun generateSkinWithAi(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(skinStatusBanner = "AI Skin Generator: синтез 64×64 развертки по промпту...")
            }
            val geminiColors = geminiAssistant.suggestSkinColorsWithGemini(prompt)
            val generated = ProceduralAiSkinSynthesizer.generateFromPrompt(
                prompt = prompt,
                format = _uiState.value.skinFormat,
                overridePrimaryHex = geminiColors?.first,
                overrideAccentHex = geminiColors?.second
            )
            pushUndoSnapshot(_uiState.value.skinPixels)
            val modeNote = if (geminiColors != null) "Гибридный Gemini + UV" else "Локальный процедурный UV"
            _uiState.update {
                it.copy(
                    skinPixels = generated,
                    skinStatusBanner = "Скин сгенерирован ($modeNote): «$prompt»"
                )
            }
        }
    }

    fun exportCurrentSkinPng() {
        val state = _uiState.value
        val activeUser = state.accounts.firstOrNull { it.isActive }?.username ?: "monolith_skin"
        val file = skinClient.exportSkinToPng(
            filesDir = minecraftRootDir,
            fileName = "${activeUser}_${state.skinFormat.width}x${state.skinFormat.height}.png",
            pixels = state.skinPixels,
            format = state.skinFormat
        )
        _uiState.update {
            it.copy(skinStatusBanner = "PNG успешно сохранен: ${file.absolutePath}")
        }
    }

    // --- 5. Dual Account System (Microsoft OAuth2 + Offline UUID v3) ---

    fun addOfflineAccount(username: String) {
        if (username.isBlank()) return
        val newAcc = authManager.createOfflineAccount(username)
        _uiState.update { state ->
            val deactivated = state.accounts.map { it.copy(isActive = false) }
            val updated = listOf(newAcc) + deactivated.filterNot { it.username.equals(newAcc.username, ignoreCase = true) }
            authManager.saveAccounts(appContext.filesDir, updated)
            state.copy(
                accounts = updated,
                authStatusMessage = "Создан Offline-профиль ${newAcc.username} (UUID v3: ${newAcc.uuid})"
            )
        }
    }

    fun loginWithMicrosoftOAuth(codeOrGamertag: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(authStatusMessage = "Проверка Microsoft / Xbox Live OAuth2...") }
            val result = authManager.authenticateMicrosoftOAuthCode(codeOrGamertag)
            result.onSuccess { msaAcc ->
                _uiState.update { state ->
                    val deactivated = state.accounts.map { it.copy(isActive = false) }
                    val updated = listOf(msaAcc) + deactivated.filterNot { it.id == msaAcc.id }
                    authManager.saveAccounts(appContext.filesDir, updated)
                    state.copy(
                        accounts = updated,
                        authStatusMessage = "Авторизован профиль Microsoft/Mojang: ${msaAcc.username}"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(authStatusMessage = "Ошибка OAuth2: ${err.message}")
                }
            }
        }
    }

    fun setActiveAccount(accountId: String) {
        _uiState.update { state ->
            val updated = state.accounts.map { it.copy(isActive = it.id == accountId) }
            authManager.saveAccounts(appContext.filesDir, updated)
            state.copy(accounts = updated)
        }
    }

    fun deleteAccount(accountId: String) {
        _uiState.update { state ->
            val remaining = state.accounts.filterNot { it.id == accountId }
            val finalAccounts = if (remaining.isEmpty()) {
                listOf(authManager.createOfflineAccount("Steve"))
            } else if (remaining.none { it.isActive }) {
                remaining.mapIndexed { idx, acc -> acc.copy(isActive = idx == 0) }
            } else {
                remaining
            }
            authManager.saveAccounts(appContext.filesDir, finalAccounts)
            state.copy(accounts = finalAccounts)
        }
    }

    fun getMicrosoftAuthorizeUrl(): String = authManager.buildMicrosoftOAuthUrl()

    // --- 6. AI Crash Analyzer (чтение реального latest.log), Hardware Tuner & Runtime Config ---

    fun analyzeLatestLogFileFromDisk() {
        val ver = _uiState.value.selectedVersion ?: return
        val latestLogTxt = File(minecraftRootDir, "latestlog.txt")
        val fallbackLog = File(minecraftRootDir, "logs/latest.log")
        val logContent = when {
            latestLogTxt.exists() && latestLogTxt.length() > 0L -> latestLogTxt.readText()
            fallbackLog.exists() && fallbackLog.length() > 0L -> fallbackLog.readText()
            else -> _uiState.value.gameLogs.joinToString("\n")
        }

        val localDiag = aiEngine.analyzeLogOrCrash(
            logText = logContent,
            targetVersion = ver,
            currentConfig = _uiState.value.runtimeConfig
        )

        _uiState.update {
            it.copy(
                crashDiagnosis = localDiag,
                isAiAnalyzing = true
            )
        }

        viewModelScope.launch {
            val geminiNote = geminiAssistant.enrichCrashAnalysisWithGemini(
                logSnippet = logContent.takeLast(3000),
                versionId = ver.id,
                javaTitle = _uiState.value.runtimeConfig.selectedJava.title
            )
            _uiState.update { state ->
                state.copy(
                    isAiAnalyzing = false,
                    crashDiagnosis = state.crashDiagnosis?.copy(aiEnhancedNotes = geminiNote)
                )
            }
        }
    }

    fun applyOneClickCrashFix() {
        val diag = _uiState.value.crashDiagnosis ?: return
        val ver = _uiState.value.selectedVersion ?: return

        _uiState.update { state ->
            val updatedRuntime = when (val action = diag.fixAction) {
                is OneClickFixAction.SwitchJavaRuntime ->
                    state.runtimeConfig.copy(
                        autoSelectJava = true,
                        selectedJava = action.targetJava
                    )
                is OneClickFixAction.AdjustHeapMemory ->
                    state.runtimeConfig.copy(allocatedMemoryMb = action.targetHeapMb)
                is OneClickFixAction.SwitchGlRenderer ->
                    state.runtimeConfig.copy(renderer = action.targetRenderer)
                OneClickFixAction.RepairAssetIntegrity ->
                    state.runtimeConfig
            }

            val fixedLogs = state.gameLogs + listOf(
                "[AI-AutoFix]: Применено исправление: ${diag.fixAction.buttonLabel}",
                "[MinecraftLauncherCore]: Конфигурация JVM обновлена для ${ver.id}."
            )

            val healthyDiag = aiEngine.analyzeLogOrCrash(
                logText = fixedLogs.joinToString("\n"),
                targetVersion = ver,
                currentConfig = updatedRuntime
            )

            state.copy(
                runtimeConfig = updatedRuntime,
                gameLogs = fixedLogs,
                crashDiagnosis = healthyDiag
            )
        }
    }

    fun applyAiHardwareTuneProfile(targetFps: Int) {
        val ver = _uiState.value.selectedVersion ?: return
        val tuned = aiEngine.inspectAndTuneHardware(appContext, ver, targetFps)
        _uiState.update { state ->
            state.copy(
                hardwareProfile = tuned,
                runtimeConfig = state.runtimeConfig.copy(
                    autoSelectJava = true,
                    selectedJava = ver.recommendedJava,
                    allocatedMemoryMb = tuned.recommendedHeapMb,
                    renderer = tuned.recommendedRenderer,
                    gcAlgorithm = tuned.recommendedGc,
                    targetFps = targetFps,
                    customJvmFlags = tuned.generatedFlags
                )
            )
        }
    }

    fun updateRuntimeAutoJava(auto: Boolean) {
        _uiState.update { state ->
            state.copy(runtimeConfig = state.runtimeConfig.copy(autoSelectJava = auto))
        }
    }

    fun updateManualJavaVersion(javaMajor: JavaRuntimeMajor) {
        _uiState.update { state ->
            state.copy(
                runtimeConfig = state.runtimeConfig.copy(
                    autoSelectJava = false,
                    selectedJava = javaMajor
                )
            )
        }
    }

    fun updateGlRenderer(renderer: MobileGlRenderer) {
        _uiState.update { state ->
            state.copy(runtimeConfig = state.runtimeConfig.copy(renderer = renderer))
        }
    }

    fun updateAllocatedHeapMb(heapMb: Int) {
        _uiState.update { state ->
            state.copy(runtimeConfig = state.runtimeConfig.copy(allocatedMemoryMb = heapMb.coerceIn(768, 6144)))
        }
    }

    fun updateGcAlgorithm(gc: GcAlgorithm) {
        _uiState.update { state ->
            state.copy(runtimeConfig = state.runtimeConfig.copy(gcAlgorithm = gc))
        }
    }

    fun updateResolutionScale(percent: Int) {
        _uiState.update { state ->
            state.copy(runtimeConfig = state.runtimeConfig.copy(resolutionScalePercent = percent.coerceIn(50, 100)))
        }
    }

    // --- 7. Предпусковая диагностика (PreLaunchDiagnosticInspector) и Облачная БД Firestore ---

    fun runPreLaunchDiagnosticAndRepair(saveToCloud: Boolean = true) {
        val state = _uiState.value
        val versionId = state.selectedVersion?.id ?: "1.21.4"
        val javaMajor = state.mojangComponentsStatus.resolvedJavaMajor
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningPreLaunchCheck = true) }
            val result = preLaunchInspector.inspectAndRepairBeforeLaunch(
                versionId = versionId,
                javaMajor = javaMajor,
                config = state.runtimeConfig,
                onLog = ::appendLogLine
            )
            _uiState.update { cur ->
                cur.copy(
                    preLaunchInspection = result,
                    isRunningPreLaunchCheck = false,
                    runtimeConfig = cur.runtimeConfig.copy(allocatedMemoryMb = result.recommendedHeapMb)
                )
            }
            if (saveToCloud) {
                val summaryText = result.checks.joinToString("; ") { "${it.code}=${it.severity.name}" }
                cloudRepository.saveDiagnosticReport(
                    versionId = versionId,
                    javaMajor = javaMajor,
                    totalChecks = result.checks.size,
                    repairedIssuesCount = result.repairedIssuesCount,
                    summary = summaryText.take(950),
                    status = result.overallStatus
                ).onSuccess {
                    appendLogLine("[Firestore/CloudDB]: Отчет предпусковой диагностики сохранен в облачную БД.")
                }
            }
        }
    }

    fun saveCurrentLaunchProfileToCloud(customProfileName: String) {
        val state = _uiState.value
        val versionId = state.selectedVersion?.id ?: "1.21.4"
        val javaMajor = if (state.runtimeConfig.autoSelectJava) {
            state.mojangComponentsStatus.resolvedJavaMajor
        } else {
            state.runtimeConfig.selectedJava.major
        }
        val title = customProfileName.trim().ifBlank {
            "Minecraft $versionId (${state.runtimeConfig.renderer.title})"
        }
        viewModelScope.launch {
            _uiState.update { it.copy(cloudSyncStatus = "Сохранение профиля «$title» в Firestore...") }
            val res = cloudRepository.createLaunchProfile(
                profileName = title,
                versionId = versionId,
                javaMajor = javaMajor,
                allocatedMemoryMb = state.runtimeConfig.allocatedMemoryMb,
                rendererId = state.runtimeConfig.renderer.id,
                gcAlgorithm = state.runtimeConfig.gcAlgorithm.name,
                customJvmFlags = state.runtimeConfig.customJvmFlags
            )
            res.onSuccess { docId ->
                appendLogLine("[Firestore/CloudDB]: Сохранен профиль запуска «$title» (id=$docId)")
                _uiState.update { it.copy(cloudSyncStatus = "Профиль «$title» сохранен в облачной БД") }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(cloudSyncStatus = "Ошибка сохранения в БД: ${err.localizedMessage ?: "отказ доступа"}")
                }
            }
        }
    }

    fun applyCloudLaunchProfile(record: LaunchProfileRecord) {
        val targetJava = JavaRuntimeMajor.fromMajor(record.javaMajor.toInt())
        val targetRenderer = MobileGlRenderer.entries.firstOrNull { it.id == record.rendererId }
            ?: MobileGlRenderer.GL4ES_114
        val targetGc = GcAlgorithm.entries.firstOrNull { it.name == record.gcAlgorithm }
            ?: GcAlgorithm.G1GC
        val matchedVersion = _uiState.value.versions.firstOrNull { it.id == record.versionId }

        _uiState.update { state ->
            state.copy(
                selectedVersion = matchedVersion ?: state.selectedVersion,
                runtimeConfig = state.runtimeConfig.copy(
                    autoSelectJava = false,
                    selectedJava = targetJava,
                    allocatedMemoryMb = record.allocatedMemoryMb.toInt().coerceIn(768, 6144),
                    renderer = targetRenderer,
                    gcAlgorithm = targetGc,
                    customJvmFlags = record.customJvmFlags
                ),
                cloudSyncStatus = "Применен облачный профиль «${record.profileName}» (${record.versionId})"
            )
        }
        appendLogLine("[Firestore/CloudDB]: Загружен профиль «${record.profileName}» -> ${record.versionId}, -Xmx${record.allocatedMemoryMb}M")
    }

    fun deleteCloudLaunchProfile(profileId: String) {
        viewModelScope.launch {
            cloudRepository.deleteLaunchProfile(profileId).onSuccess {
                _uiState.update { it.copy(cloudSyncStatus = "Профиль удален из облачной БД Firestore") }
            }
        }
    }
}
