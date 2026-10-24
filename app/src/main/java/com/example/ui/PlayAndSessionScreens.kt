package com.example.ui

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
        // Left Column (54% width): Header + Splash Quote + Era Filter + Mojang Manifest V2 List
        Surface(
            modifier = Modifier
                .weight(0.54f)
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
                // Brand Header + Bouncing Yellow Minecraft Splash Text
                SplashBrandHeader(
                    splashQuote = state.splashQuote,
                    isRefreshing = state.isFetchingManifest,
                    onSplashClick = { viewModel.cycleSplashQuote() },
                    onRefreshManifest = { viewModel.refreshMojangManifest() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Era Filter Chips (Vanilla Only: All, Release, Snapshot, Beta, Alpha, Infdev, Classic, Pre-Classic)
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

                // Compact Search Input
                OutlinedTextField(
                    value = state.versionSearchQuery,
                    onValueChange = { viewModel.updateVersionSearchQuery(it) },
                    placeholder = { Text("Поиск Vanilla версии (напр. 1.21.4, b1.7.3, a1.2.6, rd-132211)...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("version_search_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Version Catalog List
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

        // Right Column (46% width): Selected Version Spec + Pojav Core Routing + Launch Action
        Surface(
            modifier = Modifier
                .weight(0.46f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 2.dp
        ) {
            val selected = state.selectedVersion
            val activeAccount = state.accounts.firstOrNull { it.isActive } ?: state.accounts.firstOrNull()
            val effectiveJava = if (state.runtimeConfig.autoSelectJava) {
                selected?.recommendedJava ?: state.runtimeConfig.selectedJava
            } else {
                state.runtimeConfig.selectedJava
            }

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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "КОНФИГУРАЦИЯ ЗАПУСКА",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldPrimary
                            )
                            Text(
                                text = selected?.let { "Minecraft ${it.id}" } ?: "Выберите версию",
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                        if (selected != null) {
                            EraBadgePill(era = selected.era)
                        }
                    }

                    if (selected != null) {
                        Text(
                            text = selected.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Technical Matrix Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TechSpecRow("Среда OpenJDK", effectiveJava.title)
                            TechSpecRow("Рендерер GPU", state.runtimeConfig.renderer.title)
                            TechSpecRow("Пул памяти JVM", "-Xmx${state.runtimeConfig.allocatedMemoryMb}M (${state.runtimeConfig.gcAlgorithm.title})")
                            TechSpecRow("Точка входа", selected.mainClass)
                            TechSpecRow("SHA-1 Манифеста", selected.sha1.take(18) + "...")
                            TechSpecRow(
                                "Активный профиль",
                                "${activeAccount?.username ?: "Steve"} (${activeAccount?.mode?.badge ?: "Offline"})"
                            )
                        }

                        // Quick Action Shortcuts
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.navigateTo(LauncherDestination.AI_ENGINEER) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quick_ai_tune_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_ai_spark),
                                    contentDescription = "AI Тюнер",
                                    modifier = Modifier.size(16.dp),
                                    tint = SplashGold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("AI Тюнер", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.navigateTo(LauncherDestination.TOUCH_CONTROLLER) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quick_controller_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_gamepad),
                                    contentDescription = "Раскладка",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Управление", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Asset Verification Progress & Launch Button
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedVisibility(visible = state.prepareProgress != null) {
                        val prog = state.prepareProgress
                        if (prog != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = prog.stageTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { prog.progressFraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = EmeraldPrimary
                                )
                                Text(
                                    text = prog.currentFile,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.launchSelectedVanillaVersion() },
                        enabled = state.prepareProgress == null && selected != null,
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
                        Text(
                            text = "ЗАПУСТИТЬ VANILLA ${selected?.id.orEmpty()}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
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
                        text = "POJAV CORE",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                // Authentic Bouncing Yellow Splash Quote
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
 * Полноэкранная игровая сессия PojavLauncher Core с интерактивным рендером,
 * F3 телеметрией, вызываемой консолью latest.log и сенсорным контроллером.
 */
@Composable
fun GameSessionLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val selectedVersion = state.selectedVersion
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090D10))
            .testTag("game_session_screen")
    ) {
        // 1. Live Interactive Voxel World / GL4ES Viewport
        Canvas(modifier = Modifier.fillMaxSize()) {
            val horizonY = (size.height * (0.48f + (state.cameraPitch / 180f))).coerceIn(size.height * 0.15f, size.height * 0.85f)
            // Sky gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF10253E), Color(0xFF1E3F66)),
                    startY = 0f,
                    endY = horizonY
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, horizonY)
            )
            // Ground voxel turf gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF1F5E34), Color(0xFF12351E)),
                    startY = horizonY,
                    endY = size.height
                ),
                topLeft = Offset(0f, horizonY),
                size = Size(size.width, size.height - horizonY)
            )

            // Perspective grid lines reacting to WASD & Camera Yaw
            val yawOffset = (state.cameraYaw % 45f) / 45f
            for (i in -8..8) {
                val topX = size.width / 2f + (i - yawOffset) * 28f
                val bottomX = size.width / 2f + (i - yawOffset) * 190f
                drawLine(
                    color = Color(0x335CE08A),
                    start = Offset(topX, horizonY),
                    end = Offset(bottomX, size.height),
                    strokeWidth = 1.5f
                )
            }

            // Crosshair in center
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawLine(Color.White, Offset(cx - 10f, cy), Offset(cx + 10f, cy), strokeWidth = 2f)
            drawLine(Color.White, Offset(cx, cy - 10f), Offset(cx, cy + 10f), strokeWidth = 2f)
        }

        // 2. F3 Debug HUD (top-left)
        AnimatedVisibility(
            visible = state.showSessionDebugF3,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xAA0D1217))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Minecraft ${selectedVersion?.id ?: "1.21.4"} (Monolith PojavCore / Vanilla)",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = EmeraldPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.runtimeConfig.targetFps} fps • ${state.runtimeConfig.renderer.title} • Mem: ${state.runtimeConfig.allocatedMemoryMb}MB",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color.White,
                    fontSize = 10.sp
                )
                Text(
                    text = "XYZ: ${(state.analogVectorX * 12f).roundToInt()} / 64 / ${(state.analogVectorY * 12f).roundToInt()} • Yaw/Pitch: ${state.cameraYaw.roundToInt()}° / ${state.cameraPitch.roundToInt()}°",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color(0xFF9AA8B6),
                    fontSize = 10.sp
                )
                Text(
                    text = "GLFW Input: ${state.lastPressedGlfwKey}",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = SplashGold,
                    fontSize = 10.sp
                )
            }
        }

        // 3. Touch Controller Overlay
        TouchControllerOverlay(
            nodes = state.controllerNodes,
            isEditMode = false,
            selectedNodeId = null,
            snapToGrid = state.controllerSnapToGrid,
            onSelectNode = {},
            onUpdateNodePosition = { _, _, _ -> },
            onKeyEvent = { binding, pressed -> viewModel.onGlfwKeyEvent(binding, pressed) },
            onAnalogMove = { vx, vy -> viewModel.onAnalogMove(vx, vy) },
            onCameraLookDelta = { dx, dy -> viewModel.onCameraLookDelta(dx, dy) }
        )

        // 4. Top-Right Session Controls (Console Log Drawer + AI Crash Test + Return)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.toggleSessionConsoleDrawer() },
                modifier = Modifier
                    .height(34.dp)
                    .testTag("toggle_console_drawer_button")
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_console),
                    contentDescription = "Консоль",
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("latest.log", fontSize = 11.sp, fontFamily = JetBrainsMonoFontFamily)
            }
            OutlinedButton(
                onClick = { viewModel.navigateTo(LauncherDestination.AI_ENGINEER) },
                modifier = Modifier
                    .height(34.dp)
                    .testTag("session_open_ai_analyzer_button")
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_ai_spark),
                    contentDescription = "AI Анализ",
                    modifier = Modifier.size(15.dp),
                    tint = SplashGold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("AI Лог", fontSize = 11.sp)
            }
        }

        // 5. Collapsible Live Console Log Drawer
        AnimatedVisibility(
            visible = state.showSessionConsoleDrawer,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(155.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
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
                        text = "POJAV PROCESS CONSOLE (latest.log)",
                        fontFamily = JetBrainsMonoFontFamily,
                        color = EmeraldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "[Тест OOM сбоя]",
                            color = SplashGold,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            modifier = Modifier.clickable {
                                viewModel.simulateCrashScenarioInLog("HEAP_OOM")
                            }
                        )
                        Text(
                            text = "[Закрыть]",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            modifier = Modifier.clickable {
                                viewModel.toggleSessionConsoleDrawer()
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.gameLogs) { line ->
                        Text(
                            text = line,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = if (line.contains("FATAL") || line.contains("ERROR")) {
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
        // Left Interactive Viewport (64% width)
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
                onCameraLookDelta = { dx, dy -> viewModel.onCameraLookDelta(dx, dy) }
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

        // Right Inspector Column (36% width)
        Surface(
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
