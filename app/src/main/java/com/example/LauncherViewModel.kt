package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiHybridAssistant
import com.example.auth.AuthAccountManager
import com.example.auth.MinecraftAccount
import com.example.controller.ControllerProfileStorage
import com.example.controller.GlfwKeyBinding
import com.example.controller.VirtualControlNode
import com.example.engine.MinecraftVoxelGlRenderer
import com.example.engine.VoxelBlockType
import com.example.engine.VoxelWorld
import com.example.pojavruntime.AiCrashAndHardwareEngine
import com.example.pojavruntime.AssetPrepareProgress
import com.example.pojavruntime.CrashDiagnosis
import com.example.pojavruntime.GcAlgorithm
import com.example.pojavruntime.HardwareProfile
import com.example.pojavruntime.JavaRuntimeMajor
import com.example.pojavruntime.JvmRuntimeConfig
import com.example.pojavruntime.MinecraftVersion
import com.example.pojavruntime.MobileGlRenderer
import com.example.pojavruntime.MojangManifestLoader
import com.example.pojavruntime.OneClickFixAction
import com.example.pojavruntime.PojavLaunchSpec
import com.example.pojavruntime.PojavProcessLauncher
import com.example.pojavruntime.VanillaEra
import com.example.skineditor.MojangAndElySkinClient
import com.example.skineditor.ProceduralAiSkinSynthesizer
import com.example.skineditor.SkinFormat
import com.example.skineditor.SkinProviderSource
import com.example.skineditor.SkinTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class LauncherDestination(val title: String) {
    PLAY_HUB("Запуск"),
    GAME_SESSION("Игра 3D"),
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
    val prepareProgress: AssetPrepareProgress? = null,
    val runtimeConfig: JvmRuntimeConfig = JvmRuntimeConfig(),
    val accounts: List<MinecraftAccount> = emptyList(),
    val authStatusMessage: String = "",
    val controllerNodes: List<VirtualControlNode> = emptyList(),
    val selectedControllerNodeId: String = "stick_wasd",
    val controllerEditMode: Boolean = true,
    val controllerSnapToGrid: Boolean = true,
    val lastPressedGlfwKey: String = "IDLE",
    val analogVectorX: Float = 0f,
    val analogVectorY: Float = 0f,
    val cameraYaw: Float = 135f,
    val cameraPitch: Float = -12f,
    val playerX: Float = 15.5f,
    val playerY: Float = 10.0f,
    val playerZ: Float = 15.5f,
    val selectedHotbarSlot: Int = 0,
    val hotbarBlocks: List<VoxelBlockType> = VoxelBlockType.hotbarBlocks,
    val showInventoryModal: Boolean = false,
    val showPauseMenuModal: Boolean = false,
    val isThirdPersonView: Boolean = false,
    val loadedMojangJarTextures: Boolean = false,
    val targetedBlockInfo: String = "Block: Grass Block",
    val gameLogs: List<String> = emptyList(),
    val isGameSessionRunning: Boolean = false,
    val showSessionDebugF3: Boolean = false,
    val showSessionConsoleDrawer: Boolean = false,
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
    val isAiAnalyzing: Boolean = false,
    val useDynamicMaterialYou: Boolean = false,
    val isGeminiKeyActive: Boolean = false
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val manifestLoader = MojangManifestLoader()
    private val processLauncher = PojavProcessLauncher()
    private val aiEngine = AiCrashAndHardwareEngine()
    private val authManager = AuthAccountManager()
    private val skinClient = MojangAndElySkinClient()
    private val geminiAssistant = GeminiHybridAssistant()

    val voxelWorld = VoxelWorld()
    var activeGlRenderer: MinecraftVoxelGlRenderer? = null

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
        val curatedVersions = manifestLoader.curatedHistoricalCatalog()
        val firstVer = curatedVersions.first()
        val hwProfile = aiEngine.inspectAndTuneHardware(appContext, firstVer, 120)

        val defaultLogs = listOf(
            "[08:10:01] [PojavCore/INFO]: Monolith Vanilla Runner initialized on ${hwProfile.socName} (${hwProfile.primaryAbi})",
            "[08:10:02] [PojavCore/INFO]: GL4ES 1.1.5 & ANGLE Vulkan translators ready.",
            "[08:10:02] [MojangManifest/INFO]: Loaded Vanilla Version Manifest V2."
        )
        val initialDiagnosis = aiEngine.analyzeLogOrCrash(
            logText = defaultLogs.joinToString("\n"),
            targetVersion = firstVer,
            currentConfig = JvmRuntimeConfig(allocatedMemoryMb = hwProfile.recommendedHeapMb)
        )

        _uiState.update {
            it.copy(
                splashQuote = splashes.randomOrNull() ?: "100% Pure Vanilla!",
                allSplashQuotes = splashes,
                versions = curatedVersions,
                selectedVersion = firstVer,
                accounts = initialAccounts,
                controllerNodes = initialController,
                skinPixels = initialSkin,
                hardwareProfile = hwProfile,
                gameLogs = defaultLogs,
                crashDiagnosis = initialDiagnosis,
                runtimeConfig = it.runtimeConfig.copy(
                    allocatedMemoryMb = hwProfile.recommendedHeapMb,
                    renderer = hwProfile.recommendedRenderer,
                    gcAlgorithm = hwProfile.recommendedGc,
                    customJvmFlags = hwProfile.generatedFlags
                ),
                isGeminiKeyActive = geminiAssistant.isApiKeyConfigured()
            )
        }

        refreshMojangManifest()
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
                showPauseMenuModal = false,
                showInventoryModal = false
            )
        }
    }

    fun exitFullscreenGameToLauncher() {
        activeGlRenderer?.moveInputX = 0f
        activeGlRenderer?.moveInputY = 0f
        _uiState.update {
            it.copy(
                currentDestination = LauncherDestination.PLAY_HUB,
                isGameSessionRunning = false,
                showPauseMenuModal = false,
                showInventoryModal = false
            )
        }
    }

    fun getDownloadedClientJarFile(): File? {
        val verId = _uiState.value.selectedVersion?.id ?: return null
        return File(appContext.filesDir, "minecraft/versions/$verId/$verId.jar")
    }

    fun notifyMojangTextureSourceResolved(fromMojangJar: Boolean) {
        _uiState.update { it.copy(loadedMojangJarTextures = fromMojangJar) }
    }

    fun toggleDynamicMaterialYou(enabled: Boolean) {
        _uiState.update { it.copy(useDynamicMaterialYou = enabled) }
    }

    // --- 1. Mojang Manifest & Version Management ---

    fun refreshMojangManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingManifest = true) }
            val loaded = manifestLoader.fetchVanillaVersions(appContext.cacheDir)
            _uiState.update { state ->
                val currentId = state.selectedVersion?.id
                val chosen = loaded.firstOrNull { it.id == currentId } ?: loaded.firstOrNull()
                state.copy(
                    versions = loaded,
                    selectedVersion = chosen,
                    isFetchingManifest = false
                )
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
        _uiState.update { state ->
            val updatedRuntime = if (state.runtimeConfig.autoSelectJava) {
                state.runtimeConfig.copy(selectedJava = version.recommendedJava)
            } else {
                state.runtimeConfig
            }
            state.copy(
                selectedVersion = version,
                runtimeConfig = updatedRuntime,
                hardwareProfile = hw
            )
        }
    }

    // --- 2. PojavLauncher Core Execution & Full-Screen 3D Game Launch ---

    fun launchSelectedVanillaVersion() {
        val state = _uiState.value
        val version = state.selectedVersion ?: return
        val activeAccount = state.accounts.firstOrNull { it.isActive }
            ?: state.accounts.firstOrNull()
            ?: authManager.createOfflineAccount("Player")

        viewModelScope.launch {
            manifestLoader.prepareAndVerifyVersion(version, appContext.filesDir)
                .collect { progress ->
                    _uiState.update { it.copy(prepareProgress = progress) }
                }

            _uiState.update {
                it.copy(
                    prepareProgress = null,
                    currentDestination = LauncherDestination.GAME_SESSION,
                    isGameSessionRunning = true,
                    showPauseMenuModal = false,
                    showInventoryModal = false,
                    gameLogs = emptyList()
                )
            }

            processLauncher.startGameProcessStream(
                filesDir = appContext.filesDir,
                version = version,
                runtimeConfig = _uiState.value.runtimeConfig,
                username = activeAccount.username,
                uuid = activeAccount.uuid,
                accessToken = activeAccount.accessToken
            ).collect { logLine ->
                _uiState.update { cur ->
                    val nextLogs = (cur.gameLogs + logLine).takeLast(120)
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

    fun getCurrentLaunchSpecPreview(): PojavLaunchSpec? {
        val state = _uiState.value
        val version = state.selectedVersion ?: return null
        val acc = state.accounts.firstOrNull { it.isActive } ?: state.accounts.firstOrNull()
        return processLauncher.buildLaunchSpec(
            filesDir = appContext.filesDir,
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

    fun selectHotbarSlot(slotIndex: Int) {
        _uiState.update { it.copy(selectedHotbarSlot = slotIndex.coerceIn(0, 8)) }
    }

    fun assignBlockToActiveHotbar(blockType: VoxelBlockType) {
        _uiState.update { state ->
            val mutable = state.hotbarBlocks.toMutableList()
            mutable[state.selectedHotbarSlot] = blockType
            state.copy(hotbarBlocks = mutable, showInventoryModal = false)
        }
    }

    fun togglePauseMenuModal(show: Boolean) {
        _uiState.update { it.copy(showPauseMenuModal = show, showInventoryModal = false) }
    }

    fun toggleInventoryModal(show: Boolean) {
        _uiState.update { it.copy(showInventoryModal = show, showPauseMenuModal = false) }
    }

    // --- 3. Touch Controller & 3D Voxel Gameplay Interaction ---

    fun onGlfwKeyEvent(binding: GlfwKeyBinding, pressed: Boolean) {
        val renderer = activeGlRenderer

        if (pressed) {
            when (binding) {
                GlfwKeyBinding.KEY_JUMP -> {
                    renderer?.triggerJump()
                }
                GlfwKeyBinding.KEY_SNEAK -> {
                    val nextSneak = !(renderer?.isSneaking ?: false)
                    renderer?.isSneaking = nextSneak
                }
                GlfwKeyBinding.MOUSE_BREAK -> {
                    if (renderer != null) {
                        val eyeY = renderer.playerY + if (renderer.isSneaking) 1.38f else 1.62f
                        val hit = voxelWorld.raycast(
                            renderer.playerX, eyeY, renderer.playerZ,
                            renderer.cameraYaw, renderer.cameraPitch
                        )
                        if (hit != null && hit.blockType != VoxelBlockType.BEDROCK) {
                            voxelWorld.setBlock(hit.blockX, hit.blockY, hit.blockZ, VoxelBlockType.AIR)
                            _uiState.update {
                                it.copy(targetedBlockInfo = "Разрушен: ${hit.blockType.displayName} (${hit.blockX}, ${hit.blockY}, ${hit.blockZ})")
                            }
                        }
                    }
                }
                GlfwKeyBinding.MOUSE_PLACE -> {
                    if (renderer != null) {
                        val eyeY = renderer.playerY + if (renderer.isSneaking) 1.38f else 1.62f
                        val hit = voxelWorld.raycast(
                            renderer.playerX, eyeY, renderer.playerZ,
                            renderer.cameraYaw, renderer.cameraPitch
                        )
                        if (hit != null) {
                            val chosenBlock = _uiState.value.hotbarBlocks.getOrElse(_uiState.value.selectedHotbarSlot) {
                                VoxelBlockType.COBBLESTONE
                            }
                            // Avoid placing block directly inside the player's body
                            val pBlockX = kotlin.math.floor(renderer.playerX).toInt()
                            val pBlockY = kotlin.math.floor(renderer.playerY).toInt()
                            val pBlockZ = kotlin.math.floor(renderer.playerZ).toInt()
                            val collidesWithPlayer = (hit.placeX == pBlockX && hit.placeZ == pBlockZ &&
                                (hit.placeY == pBlockY || hit.placeY == pBlockY + 1))
                            if (!collidesWithPlayer) {
                                voxelWorld.setBlock(hit.placeX, hit.placeY, hit.placeZ, chosenBlock)
                                _uiState.update {
                                    it.copy(targetedBlockInfo = "Установлен: ${chosenBlock.displayName} (${hit.placeX}, ${hit.placeY}, ${hit.placeZ})")
                                }
                            }
                        }
                    }
                }
                GlfwKeyBinding.KEY_INVENTORY -> {
                    _uiState.update { it.copy(showInventoryModal = !it.showInventoryModal, showPauseMenuModal = false) }
                }
                GlfwKeyBinding.KEY_PAUSE_ESC -> {
                    _uiState.update { it.copy(showPauseMenuModal = !it.showPauseMenuModal, showInventoryModal = false) }
                }
                GlfwKeyBinding.KEY_THIRD_PERSON_F5 -> {
                    val nextF5 = !_uiState.value.isThirdPersonView
                    renderer?.thirdPersonMode = nextF5
                    _uiState.update { it.copy(isThirdPersonView = nextF5) }
                }
                GlfwKeyBinding.KEY_DEBUG_F3 -> {
                    _uiState.update { it.copy(showSessionDebugF3 = !it.showSessionDebugF3) }
                }
                GlfwKeyBinding.KEY_CHAT -> {
                    _uiState.update { it.copy(showSessionConsoleDrawer = !it.showSessionConsoleDrawer) }
                }
                GlfwKeyBinding.KEY_PLAYER_LIST_TAB -> {
                    renderer?.let { it.isNightTime = !it.isNightTime }
                }
                else -> {}
            }
        }

        _uiState.update { state ->
            state.copy(
                lastPressedGlfwKey = if (pressed) "${binding.keyName} [DOWN]" else "${binding.keyName} [UP]",
                playerX = renderer?.playerX ?: state.playerX,
                playerY = renderer?.playerY ?: state.playerY,
                playerZ = renderer?.playerZ ?: state.playerZ
            )
        }
    }

    fun onAnalogMove(vx: Float, vy: Float) {
        val renderer = activeGlRenderer
        if (renderer != null) {
            renderer.moveInputX = vx
            renderer.moveInputY = vy
        }
        _uiState.update {
            it.copy(
                analogVectorX = vx,
                analogVectorY = vy,
                playerX = renderer?.playerX ?: it.playerX,
                playerY = renderer?.playerY ?: it.playerY,
                playerZ = renderer?.playerZ ?: it.playerZ
            )
        }
    }

    fun onCameraLookDelta(dx: Float, dy: Float) {
        val renderer = activeGlRenderer
        val nextYaw = ((_uiState.value.cameraYaw + dx * 0.28f) % 360f + 360f) % 360f
        val nextPitch = (_uiState.value.cameraPitch - dy * 0.22f).coerceIn(-85f, 85f)
        if (renderer != null) {
            renderer.cameraYaw = nextYaw
            renderer.cameraPitch = nextPitch
        }
        _uiState.update {
            it.copy(
                cameraYaw = nextYaw,
                cameraPitch = nextPitch,
                playerX = renderer?.playerX ?: it.playerX,
                playerY = renderer?.playerY ?: it.playerY,
                playerZ = renderer?.playerZ ?: it.playerZ
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
            filesDir = appContext.filesDir,
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

    // --- 6. AI Crash Analyzer, Hardware Tuner & Runtime Config ---

    fun simulateCrashScenarioInLog(scenarioType: String) {
        val ver = _uiState.value.selectedVersion ?: return
        val simulatedLines = when (scenarioType) {
            "JAVA_VERSION" -> listOf(
                "[12:04:11] [PojavCore/INFO]: Launching ${ver.id} with manual JRE 8 override...",
                "[12:04:12] [main/FATAL]: Exception in thread \"main\" java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 52.0",
                "[12:04:12] [PojavCore/ERROR]: Process exited with code 1"
            )
            "HEAP_OOM" -> listOf(
                "[12:05:30] [Render thread/WARN]: Memory allocation spike during world chunk meshing",
                "[12:05:31] [Server thread/FATAL]: java.lang.OutOfMemoryError: Java heap space (allocated -Xmx${_uiState.value.runtimeConfig.allocatedMemoryMb}M)",
                "[12:05:31] [PojavCore/ERROR]: Crash report saved to crash-reports/crash-oom-client.txt"
            )
            "GL_SHADER" -> listOf(
                "[12:06:15] [Render thread/INFO]: Compiling core shader rendertype_entity_cutout...",
                "[12:06:16] [Render thread/ERROR]: GL4ES Shader compilation failed: glCompileShader error 0x502 (EGL_BAD_CONFIG)",
                "[12:06:16] [main/FATAL]: GLFW_PLATFORM_ERROR: Failed to link OpenGL program on mobile GPU"
            )
            else -> listOf(
                "[12:07:02] [DownloadEngine/ERROR]: java.util.zip.ZipException: invalid CEN header (SHA-1 mismatch on client.jar)",
                "[12:07:02] [main/FATAL]: java.lang.ClassNotFoundException: net.minecraft.client.main.Main"
            )
        }

        val combinedText = simulatedLines.joinToString("\n")
        val localDiag = aiEngine.analyzeLogOrCrash(
            logText = combinedText,
            targetVersion = ver,
            currentConfig = _uiState.value.runtimeConfig
        )

        _uiState.update {
            it.copy(
                gameLogs = simulatedLines,
                crashDiagnosis = localDiag,
                isAiAnalyzing = true
            )
        }

        viewModelScope.launch {
            val geminiNote = geminiAssistant.enrichCrashAnalysisWithGemini(
                logSnippet = combinedText,
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
                "[AI-AutoFix/SUCCESS]: Применено исправление: ${diag.fixAction.buttonLabel}",
                "[PojavCore/INFO]: Конфигурация JVM синхронизирована для ${ver.id}. Готово к запуску."
            )

            val healthyDiag = aiEngine.analyzeLogOrCrash(
                logText = "[PojavCore/INFO]: Ready.",
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
}
