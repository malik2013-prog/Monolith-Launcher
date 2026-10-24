package com.example.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.LauncherDestination
import com.example.LauncherUiState
import com.example.LauncherViewModel
import com.example.R
import com.example.controller.TouchControllerOverlay
import com.example.pojavruntime.MinecraftVersion
import com.example.pojavruntime.VanillaEra
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SplashGold
import kotlin.math.roundToInt

@Composable
fun PlayHubLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val filteredVersions = state.versions.filter { ver ->
        val matchesEra = state.selectedEraFilter == null || ver.era == state.selectedEraFilter
        val matchesQuery = state.versionSearchQuery.isBlank() ||
            ver.id.contains(state.versionSearchQuery, ignoreCase = true) ||
            ver.summary.contains(state.versionSearchQuery, ignoreCase = true)
        matchesEra && matchesQuery
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column (52% width): Header + Splash Quote + Era Filter + Real Mojang Manifest V2 List
        M3Surface(
            modifier = Modifier
                .weight(0.52f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                SplashBrandHeader(
                    splashQuote = state.splashQuote,
                    isRefreshing = state.isFetchingManifest,
                    onSplashClick = { viewModel.cycleSplashQuote() },
                    onRefreshManifest = { viewModel.refreshMojangManifest() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = state.selectedEraFilter == null,
                        onClick = { viewModel.selectEraFilter(null) },
                        label = { Text("Все эпохи (${state.versions.size})", fontSize = 12.sp) },
                        modifier = Modifier.testTag("era_chip_all")
                    )
                    VanillaEra.entries.forEach { era ->
                        FilterChip(
                            selected = state.selectedEraFilter == era,
                            onClick = { viewModel.selectEraFilter(era) },
                            label = { Text(era.displayName, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag("era_chip_${era.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = state.versionSearchQuery,
                    onValueChange = { viewModel.updateVersionSearchQuery(it) },
                    placeholder = { Text("Поиск Vanilla версии из piston-meta.mojang.com...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("version_search_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (state.isFetchingManifest && state.versions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(color = EmeraldPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Загрузка официального манифеста Mojang (version_manifest_v2.json)...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("vanilla_version_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredVersions, key = { it.id }) { version ->
                            VersionListRowItem(
                                version = version,
                                isSelected = state.selectedVersion?.id == version.id,
                                onClick = { viewModel.selectVersion(version) }
                            )
                        }
                    }
                }
            }
        }

        // Right Column (48% width): Selected Version Spec + Real Mojang Downloader + Live Console + Launch Button
        M3Surface(
            modifier = Modifier
                .weight(0.48f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 2.dp
        ) {
            val selected = state.selectedVersion
            val comp = state.mojangComponentsStatus
            val jarExists = comp.clientJarInstalled

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "MOJANG DOWNLOADER & OPENJDK ARM64",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldPrimary
                            )
                            Text(
                                text = selected?.let { "Minecraft ${it.id}" } ?: "Загрузка манифеста...",
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                        if (selected != null) {
                            EraBadgePill(era = selected.era)
                        }
                    }

                    if (selected != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TechSpecRow(
                                "OpenJDK Runtime (aarch64)",
                                "Java ${comp.resolvedJavaMajor} (${if (comp.jreInstalled) "Установлен в files/runtimes/jre_${comp.resolvedJavaMajor}" else "Скачается автоматически"})"
                            )
                            TechSpecRow("Графический мост", state.runtimeConfig.renderer.title)
                            TechSpecRow("Пул памяти JVM", "-Xmx${state.runtimeConfig.allocatedMemoryMb}M (${state.runtimeConfig.gcAlgorithm.name})")
                            TechSpecRow(
                                "Клиент versions/${selected.id}.jar",
                                if (comp.clientJarInstalled || jarExists) {
                                    "Проверен SHA-1 (${"%.1f".format(comp.clientJarSizeMb)} МБ)"
                                } else {
                                    "Не скачан (piston-data.mojang.com)"
                                }
                            )
                            TechSpecRow(
                                "Maven libraries/ & assets/objects/",
                                "JAR: ${comp.mojangLibrariesInstalledCount} шт. • Objects: ${comp.assetObjectsInstalledCount} шт."
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.installMojangComponentsOnly() },
                                enabled = state.prepareProgress == null,
                                modifier = Modifier
                                    .weight(1.15f)
                                    .testTag("install_mojang_components_button")
                            ) {
                                Text(
                                    text = if (comp.isFullyReady) "Перепроверить SHA-1 файлов" else "Скачать файлы и JRE без запуска",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.navigateTo(LauncherDestination.TOUCH_CONTROLLER) },
                                modifier = Modifier
                                    .weight(0.85f)
                                    .testTag("quick_controller_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_mouse_cursor),
                                    contentDescription = "Мышь и Управление",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Мышь / Тач", fontSize = 11.sp, maxLines = 1)
                            }
                        }

                        // Real-time Console Log Box inside PlayHub
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0A0E12))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "КОНСОЛЬ MOJANG DOWNLOADER & JRE (latest.log)",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.gameLogs.takeLast(35)) { logLine ->
                                    Text(
                                        text = logLine,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 9.sp,
                                        color = if (logLine.contains("ERROR") || logLine.contains("FATAL")) {
                                            Color(0xFFF05454)
                                        } else {
                                            Color(0xFFD0D8E0)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val prog = state.prepareProgress
                    AnimatedVisibility(visible = prog != null) {
                        if (prog != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = prog.stageTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = EmeraldPrimary
                                    )
                                    Text(
                                        text = "${"%.1f".format(prog.downloadedMb)} / ${"%.1f".format(prog.totalMb)} МБ (${prog.percentage}%)",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp,
                                        color = SplashGold,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { prog.progressFraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = EmeraldPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Файл: ${prog.currentFile}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.launchSelectedVanillaVersion() },
                        enabled = prog == null && selected != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("launch_vanilla_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = Color(0xFF051B0D)
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_play),
                            contentDescription = "Запустить игру",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val buttonLabel = when {
                            prog != null -> {
                                "${prog.currentFile} • ${"%.1f".format(prog.downloadedMb)}/${"%.1f".format(prog.totalMb)} МБ (${prog.percentage}%)"
                            }
                            comp.isFullyReady -> {
                                "ЗАПУСТИТЬ MINECRAFT ${selected?.id.orEmpty()} (JVM ARM64)"
                            }
                            else -> {
                                "СКАЧАТЬ И ЗАПУСТИТЬ ${selected?.id.orEmpty()}"
                            }
                        }
                        Text(
                            text = buttonLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplashBrandHeader(
    splashQuote: String,
    isRefreshing: Boolean,
    onSplashClick: () -> Unit,
    onRefreshManifest: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splash_scale"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF101920))
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Monolith Cube",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(38.dp)
                )
            }
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "MONOLITH VANILLA",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ARM64 JRE",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = splashQuote,
                    color = SplashGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMonoFontFamily,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            rotationZ = -4f
                        }
                        .clickable { onSplashClick() }
                        .testTag("animated_splash_quote")
                )
            }
        }

        OutlinedButton(
            onClick = onRefreshManifest,
            modifier = Modifier
                .height(36.dp)
                .testTag("refresh_manifest_button")
        ) {
            Text(
                text = if (isRefreshing) "Синхр..." else "Manifest V2",
                fontSize = 11.sp,
                fontFamily = JetBrainsMonoFontFamily
            )
        }
    }
}

@Composable
private fun VersionListRowItem(
    version: MinecraftVersion,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderCol = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outline
    val bgCol = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgCol)
            .border(if (isSelected) 1.5.dp else 1.dp, borderCol, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("version_item_${version.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = version.id,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                EraBadgePill(era = version.era)
                Text(
                    text = "Java ${version.recommendedJava.major}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = version.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp
            )
        }
        Text(
            text = version.releaseTime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EraBadgePill(era: VanillaEra) {
    val badgeColor = when (era) {
        VanillaEra.RELEASE -> EmeraldPrimary
        VanillaEra.SNAPSHOT -> SplashGold
        VanillaEra.BETA -> Color(0xFF58A6FF)
        VanillaEra.ALPHA -> Color(0xFF7EE787)
        VanillaEra.INFDEV -> Color(0xFFD2A8FF)
        VanillaEra.CLASSIC -> Color(0xFFFFA657)
        VanillaEra.PRE_CLASSIC -> Color(0xFFFF7B72)
    }
    Text(
        text = era.badgeCode,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        color = badgeColor,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColor.copy(alpha = 0.16f))
            .border(0.8.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
fun TechSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Экран нативного вывода Minecraft: Java Edition (ARM64) через Android TextureView + JNI Surface Bridge,
 * поверх которого рисуется кастомизируемый TouchControllerOverlay и виртуальный курсор мыши.
 */
@Composable
fun GameSessionLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val selectedVersion = state.selectedVersion
    val spec = remember(selectedVersion?.id, state.runtimeConfig) {
        viewModel.getCurrentLaunchSpecPreview()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("game_session_screen")
    ) {
        // 1. Real Android TextureView passing Surface to PojavNativeBridge (libpojavexec.so / GLFW / ANGLE / GL4ES)
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    keepScreenOn = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var activeSurface: Surface? = null

                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            val surface = Surface(surfaceTexture)
                            activeSurface = surface
                            viewModel.onTextureViewSurfaceReady(surface, width, height)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            viewModel.onTextureViewSurfaceSizeChanged(width, height)
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            viewModel.onTextureViewSurfaceDestroyed()
                            activeSurface?.release()
                            activeSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("minecraft_native_texture_view")
        )

        // 2. Center Crosshair (+) when Virtual Mouse is off
        if (!state.isVirtualMouseEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawLine(Color.White, Offset(cx - 11f, cy), Offset(cx + 11f, cy), strokeWidth = 2.2f)
                drawLine(Color.White, Offset(cx, cy - 11f), Offset(cx, cy + 11f), strokeWidth = 2.2f)
            }
        }

        // 3. Touch Controller Overlay (WASD Stick + Virtual Mouse Cursor (MOUSE) + GLFW Key Dispatch)
        TouchControllerOverlay(
            nodes = state.controllerNodes,
            isEditMode = false,
            selectedNodeId = null,
            snapToGrid = state.controllerSnapToGrid,
            onSelectNode = {},
            onUpdateNodePosition = { _, _, _ -> },
            onKeyEvent = { binding, pressed -> viewModel.onGlfwKeyEvent(binding, pressed) },
            onAnalogMove = { vx, vy -> viewModel.onAnalogMove(vx, vy) },
            onCameraLookDelta = { dx, dy -> viewModel.onCameraLookDelta(dx, dy) },
            isVirtualMouseActive = state.isVirtualMouseEnabled,
            virtualMouseNormX = state.virtualMouseX,
            virtualMouseNormY = state.virtualMouseY,
            onVirtualMouseDelta = { dx, dy -> viewModel.onVirtualMouseDelta(dx, dy) },
            onVirtualMouseClick = { viewModel.onVirtualMouseClick() }
        )

        // 4. F3 Native JVM & Surface Status HUD (positioned below top bar)
        AnimatedVisibility(
            visible = state.showSessionDebugF3,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 56.dp, start = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC0B0F14))
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Minecraft ${selectedVersion?.id ?: "Vanilla"} • MainClass: ${spec?.mainClass ?: "net.minecraft.client.main.Main"}",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = EmeraldPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Surface: TextureView (${state.surfaceWidth}x${state.surfaceHeight}, Bound=${state.isSurfaceBound}) • Session Running: ${state.isGameSessionRunning}",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = SplashGold,
                    fontSize = 10.sp
                )
                Text(
                    text = "OpenJDK: ${spec?.jreHomeDir ?: "runtimes/jre"} • Classpath JARs: ${spec?.classpathEntries?.size ?: 0}",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color.White,
                    fontSize = 10.sp
                )
                Text(
                    text = "JVM Heap: -Xmx${state.runtimeConfig.allocatedMemoryMb}M (${state.runtimeConfig.renderer.title}) • Last GLFW Key: ${state.lastPressedGlfwKey}",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color(0xFFB0BEC5),
                    fontSize = 10.sp
                )
            }
        }

        // 5. Top-Right Quick Mouse Cursor, Console & Menu Buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { viewModel.toggleVirtualMouseCursor() },
                modifier = Modifier
                    .height(32.dp)
                    .testTag("toggle_virtual_mouse_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isVirtualMouseEnabled) EmeraldPrimary else Color(0xCC1E262E),
                    contentColor = if (state.isVirtualMouseEnabled) Color(0xFF041A0C) else Color.White
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mouse_cursor),
                    contentDescription = "Курсор мыши",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (state.isVirtualMouseEnabled) "Курсор: ВКЛ" else "Курсор",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = { viewModel.toggleSessionConsoleDrawer() },
                modifier = Modifier
                    .height(32.dp)
                    .testTag("toggle_console_drawer_button")
            ) {
                Text("Консоль JVM", fontSize = 11.sp, color = Color.White)
            }
            Button(
                onClick = { viewModel.togglePauseMenuModal(true) },
                modifier = Modifier
                    .height(32.dp)
                    .testTag("open_game_menu_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xCC1E262E),
                    contentColor = Color.White
                )
            ) {
                Text("Меню (ESC)", fontSize = 11.sp)
            }
        }

        // 6. Pause Menu Modal (ESC)
        AnimatedVisibility(
            visible = state.showPauseMenuModal,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEE12171C))
                    .border(1.5.dp, Color(0xFF495867), RoundedCornerShape(12.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Меню сессии — Minecraft ${selectedVersion?.id ?: "Vanilla"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Button(
                    onClick = { viewModel.togglePauseMenuModal(false) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = Color(0xFF041A0C)
                    )
                ) {
                    Text("Вернуться к окну игры (Resume)", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { viewModel.exitFullscreenGameToLauncher() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quit_to_launcher_button")
                ) {
                    Text("Завершить JVM и выйти в лаунчер", color = SplashGold, fontSize = 12.sp)
                }
            }
        }

        // 7. Collapsible Live Console Log Drawer (stdout/stderr from JVM & JNI)
        AnimatedVisibility(
            visible = state.showSessionConsoleDrawer,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(175.dp)
                .padding(horizontal = 24.dp, vertical = 42.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xEE0B0F14))
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "POJAV JVM / JNI CONSOLE (latest.log)",
                        fontFamily = JetBrainsMonoFontFamily,
                        color = EmeraldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "[Свернуть]",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        modifier = Modifier.clickable {
                            viewModel.toggleSessionConsoleDrawer()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.gameLogs) { line ->
                        Text(
                            text = line,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = if (line.contains("FATAL") || line.contains("ERROR") || line.contains("Exception")) {
                                Color(0xFFF05454)
                            } else {
                                Color(0xFFD0D8E0)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Интерактивный редактор раскладки сенсорного управления (Drag-and-Drop, Scale, Opacity, Haptic).
 */
@Composable
fun TouchControllerStudioScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val selectedNode = state.controllerNodes.firstOrNull { it.id == state.selectedControllerNodeId }
        ?: state.controllerNodes.firstOrNull()

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(0.64f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0B0F13))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
        ) {
            TouchControllerOverlay(
                nodes = state.controllerNodes,
                isEditMode = state.controllerEditMode,
                selectedNodeId = state.selectedControllerNodeId,
                snapToGrid = state.controllerSnapToGrid,
                onSelectNode = { viewModel.selectControllerNode(it) },
                onUpdateNodePosition = { id, nx, ny -> viewModel.updateControllerNodePosition(id, nx, ny) },
                onKeyEvent = { binding, pressed -> viewModel.onGlfwKeyEvent(binding, pressed) },
                onAnalogMove = { vx, vy -> viewModel.onAnalogMove(vx, vy) },
                onCameraLookDelta = { dx, dy -> viewModel.onCameraLookDelta(dx, dy) },
                isVirtualMouseActive = state.isVirtualMouseEnabled,
                virtualMouseNormX = state.virtualMouseX,
                virtualMouseNormY = state.virtualMouseY,
                onVirtualMouseDelta = { dx, dy -> viewModel.onVirtualMouseDelta(dx, dy) },
                onVirtualMouseClick = { viewModel.onVirtualMouseClick() }
            )

            Text(
                text = if (state.controllerEditMode) {
                    "РЕЖИМ РЕДАКТОРА: Перетаскивайте кнопки (Drag & Drop)"
                } else {
                    "ТЕСТОВЫЙ РЕЖИМ: Нажимайте кнопки (${state.lastPressedGlfwKey})"
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = EmeraldPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC11171D))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        M3Surface(
            modifier = Modifier
                .weight(0.36f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "РЕДАКТОР TOUCH OVERLAY",
                    style = MaterialTheme.typography.titleMedium,
                    color = EmeraldPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Режим Drag-and-Drop", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.controllerEditMode,
                        onCheckedChange = { viewModel.setControllerEditMode(it) },
                        modifier = Modifier.testTag("controller_edit_mode_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Привязка к сетке (Snap)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.controllerSnapToGrid,
                        onCheckedChange = { viewModel.setControllerSnapToGrid(it) }
                    )
                }

                if (selectedNode != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Выбран узел: ${selectedNode.binding.shortLabel}",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${selectedNode.binding.keyName} • X=${(selectedNode.normalizedX * 100).roundToInt()}% Y=${(selectedNode.normalizedY * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = "Масштаб кнопки: ${(selectedNode.scale * 100).roundToInt()}%",
                            fontSize = 12.sp
                        )
                        Slider(
                            value = selectedNode.scale,
                            onValueChange = { viewModel.updateSelectedControllerScale(it) },
                            valueRange = 0.5f..1.8f,
                            modifier = Modifier.testTag("controller_scale_slider")
                        )

                        Text(
                            text = "Непрозрачность: ${(selectedNode.opacity * 100).roundToInt()}%",
                            fontSize = 12.sp
                        )
                        Slider(
                            value = selectedNode.opacity,
                            onValueChange = { viewModel.updateSelectedControllerOpacity(it) },
                            valueRange = 0.15f..1.0f,
                            modifier = Modifier.testTag("controller_opacity_slider")
                        )

                        if (selectedNode.id != "stick_wasd") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Фиксация нажатия (Toggle)", fontSize = 12.sp)
                                Switch(
                                    checked = selectedNode.isStickyToggle,
                                    onCheckedChange = { viewModel.toggleSelectedControllerSticky(it) }
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.resetControllerLayoutToDefault() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_controller_layout_button")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_trash),
                        contentDescription = "Сброс",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Сбросить раскладку по умолчанию", fontSize = 12.sp)
                }
            }
        }
    }
}
