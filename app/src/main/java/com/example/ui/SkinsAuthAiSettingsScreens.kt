package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.LauncherUiState
import com.example.LauncherViewModel
import com.example.R
import com.example.auth.AccountMode
import com.example.pojavruntime.GcAlgorithm
import com.example.pojavruntime.JavaRuntimeMajor
import com.example.pojavruntime.MobileGlRenderer
import com.example.skineditor.SkinFormat
import com.example.skineditor.SkinPreview3DViewport
import com.example.skineditor.SkinProviderSource
import com.example.skineditor.SkinTool
import com.example.skineditor.SkinUvCanvas2D
import com.example.ui.theme.DangerRed
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SplashGold
import kotlin.math.roundToInt

/**
 * 2D/3D Студия скинов с интеграцией Mojang API + Ely.by, поддержкой второго слоя Overlay (64x64 / 64x32)
 * и встроенным AI Skin Generator.
 */
@Composable
fun SkinStudioLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    var lookupNickname by remember { mutableStateOf("Notch") }
    var aiSkinPrompt by remember { mutableStateOf("Кибер-рыцарь в обсидиановой броне с изумрудным визором") }
    var animateWalk by remember { mutableStateOf(true) }

    val walkTransition = rememberInfiniteTransition(label = "walk_cycle")
    val walkPhase by walkTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "walk_phase"
    )

    val paletteColors = remember {
        listOf(
            0xFF38C76A.toInt(),
            0xFF1F7A42.toInt(),
            0xFFFFD84D.toInt(),
            0xFFF05454.toInt(),
            0xFF2EB8D1.toInt(),
            0xFF58A6FF.toInt(),
            0xFF8B5CF6.toInt(),
            0xFFE6B89C.toInt(),
            0xFFB57C58.toInt(),
            0xFF2B2D38.toInt(),
            0xFF495867.toInt(),
            0xFFF0F6FC.toInt()
        )
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column (44%): 3D Preview + Mojang / Ely.by Fetcher + AI Skin Generator
        Surface(
            modifier = Modifier
                .weight(0.44f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "3D МОДЕЛЬ И ИМПОРТ (ELY.BY / MOJANG)",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.showSkinOverlay,
                            onClick = { viewModel.toggleSkinOverlayLayer() },
                            label = { Text("Overlay Слой", fontSize = 11.sp) },
                            modifier = Modifier.testTag("toggle_skin_overlay_chip")
                        )
                        FilterChip(
                            selected = animateWalk,
                            onClick = { animateWalk = !animateWalk },
                            label = { Text("Походка", fontSize = 11.sp) }
                        )
                    }
                }

                SkinPreview3DViewport(
                    pixels = state.skinPixels,
                    format = state.skinFormat,
                    yawDegrees = state.skinPreviewYaw,
                    pitchDegrees = state.skinPreviewPitch,
                    showOuterOverlay = state.showSkinOverlay,
                    walkPhase = if (animateWalk) walkPhase else 0f,
                    onRotateDelta = { dx, dy -> viewModel.rotateSkinPreview3D(dx, dy) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                )

                // Mojang & Ely.by Lookup Row
                OutlinedTextField(
                    value = lookupNickname,
                    onValueChange = { lookupNickname = it },
                    label = { Text("Никнейм игрока (Mojang / Ely.by)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("skin_lookup_nickname_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.fetchSkinFromProvider(
                                lookupNickname,
                                SkinProviderSource.MOJANG_OFFICIAL
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fetch_mojang_skin_button")
                    ) {
                        Text("Mojang API", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.fetchSkinFromProvider(
                                lookupNickname,
                                SkinProviderSource.ELY_BY
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fetch_elyby_skin_button")
                    ) {
                        Text("Ely.by API", fontSize = 11.sp)
                    }
                }

                // AI Skin Generator Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_ai_spark),
                            contentDescription = "AI Skin Generator",
                            tint = SplashGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "AI SKIN GENERATOR (64×64)",
                            style = MaterialTheme.typography.labelMedium,
                            color = SplashGold
                        )
                    }
                    OutlinedTextField(
                        value = aiSkinPrompt,
                        onValueChange = { aiSkinPrompt = it },
                        placeholder = { Text("Опишите образ скина...", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_skin_prompt_input")
                    )
                    Button(
                        onClick = { viewModel.generateSkinWithAi(aiSkinPrompt) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generate_ai_skin_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = Color(0xFF051C0E)
                        )
                    ) {
                        Text("Сгенерировать скин по описанию", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Right Column (56%): 2D UV Canvas (64x64 / 64x32), Palette, Eyedropper, Export PNG
        Surface(
            modifier = Modifier
                .weight(0.56f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Toolbar: Format 64x64 / 64x32 + Tools + Undo + Export PNG
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SkinFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = state.skinFormat == fmt,
                                onClick = { viewModel.switchSkinFormat(fmt) },
                                label = { Text(fmt.label, fontSize = 11.sp) },
                                modifier = Modifier.testTag("skin_format_${fmt.width}x${fmt.height}")
                            )
                        }
                        SkinTool.entries.forEach { tool ->
                            FilterChip(
                                selected = state.skinTool == tool,
                                onClick = { viewModel.selectSkinTool(tool) },
                                label = { Text(tool.label, fontSize = 11.sp) },
                                modifier = Modifier.testTag("skin_tool_${tool.name.lowercase()}")
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.undoSkinEdit() },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Undo", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.exportCurrentSkinPng() },
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("export_skin_png_button")
                        ) {
                            Text("Экспорт PNG", fontSize = 11.sp)
                        }
                    }

                    // Color Palette Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        paletteColors.forEach { argb ->
                            val isChosen = state.selectedColorArgb == argb
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(
                                        width = if (isChosen) 2.5.dp else 1.dp,
                                        color = if (isChosen) Color.White else Color(0xFF303B46),
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.selectSkinColor(argb) }
                            )
                        }
                    }
                }

                // 2D Interactive UV Canvas
                SkinUvCanvas2D(
                    pixels = state.skinPixels,
                    format = state.skinFormat,
                    showGridLines = state.showSkinGrid,
                    showBodyZoneGuides = state.showSkinZoneGuides,
                    onPixelTouch = { px, py -> viewModel.applyPixelOnSkinCanvas(px, py) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                )

                Text(
                    text = state.skinStatusBanner,
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Двойной селектор аккаунтов: Официальный Microsoft / Xbox Live OAuth2 и Offline Mode (UUID v3).
 */
@Composable
fun AccountsAuthLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedAuthTab by remember { mutableStateOf(AccountMode.OFFLINE_LOCAL) }
    var offlineNickInput by remember { mutableStateOf("VanillaBuilder") }
    var msaCodeInput by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column (48%): Saved Profiles List
        Surface(
            modifier = Modifier
                .weight(0.48f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "СОХРАНЕННЫЕ ПРОФИЛИ ИГРОКА",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.accounts, key = { it.id }) { acc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (acc.isActive) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .border(
                                    width = if (acc.isActive) 1.5.dp else 1.dp,
                                    color = if (acc.isActive) EmeraldPrimary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { viewModel.setActiveAccount(acc.id) }
                                .padding(10.dp)
                                .testTag("account_card_${acc.username}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = acc.username,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (acc.mode == AccountMode.MICROSOFT_OAUTH) "MSA OAuth2" else "Offline v3",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (acc.mode == AccountMode.MICROSOFT_OAUTH) SplashGold else EmeraldPrimary
                                    )
                                }
                                Text(
                                    text = "UUID: ${acc.uuid}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteAccount(acc.id) },
                                modifier = Modifier.testTag("delete_account_${acc.username}")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_trash),
                                    contentDescription = "Удалить аккаунт",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                if (state.authStatusMessage.isNotBlank()) {
                    Text(
                        text = state.authStatusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary
                    )
                }
            }
        }

        // Right Column (52%): Dual Selector (Microsoft / Xbox Live OAuth2 vs Offline Mode)
        Surface(
            modifier = Modifier
                .weight(0.52f)
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
                    text = "ДВОЙНОЙ СЕЛЕКТОР АВТОРИЗАЦИИ",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedAuthTab == AccountMode.OFFLINE_LOCAL,
                        onClick = { selectedAuthTab = AccountMode.OFFLINE_LOCAL },
                        label = { Text("Offline Mode (UUID v3)") },
                        modifier = Modifier.testTag("auth_tab_offline")
                    )
                    FilterChip(
                        selected = selectedAuthTab == AccountMode.MICROSOFT_OAUTH,
                        onClick = { selectedAuthTab = AccountMode.MICROSOFT_OAUTH },
                        label = { Text("Microsoft / Xbox Live OAuth2") },
                        modifier = Modifier.testTag("auth_tab_microsoft")
                    )
                }

                if (selectedAuthTab == AccountMode.OFFLINE_LOCAL) {
                    Text(
                        text = "Локальный профиль генерирует детерминированный UUID v3 по стандарту OfflinePlayer:<nickname> для одиночной игры и LAN-серверов.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = offlineNickInput,
                        onValueChange = { offlineNickInput = it },
                        label = { Text("Никнейм игрока (до 16 символов)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_username_input")
                    )
                    Button(
                        onClick = { viewModel.addOfflineAccount(offlineNickInput) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_offline_account_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = Color(0xFF051C0E)
                        )
                    ) {
                        Text("Создать и активировать Offline профиль", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = "Цепочка авторизации: Microsoft OAuth2 -> Xbox Live (XBL) -> XSTS Token -> Minecraft Services Profile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(viewModel.getMicrosoftAuthorizeUrl())
                                )
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_microsoft_oauth_browser_button")
                    ) {
                        Text("1. Открыть страницу входа Microsoft Live OAuth2")
                    }
                    OutlinedTextField(
                        value = msaCodeInput,
                        onValueChange = { msaCodeInput = it },
                        label = { Text("2. Код редиректа OAuth2 (code=M...) или лицензионный Gamertag") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("microsoft_oauth_code_input")
                    )
                    Button(
                        onClick = { viewModel.loginWithMicrosoftOAuth(msaCodeInput) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("verify_microsoft_oauth_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SplashGold,
                            contentColor = Color(0xFF1F1600)
                        )
                    ) {
                        Text("Авторизовать профиль Xbox Live / Mojang", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Практичные AI-функции: AI Crash & Log Analyzer (с исправлением в 1 клик) + AI Hardware Tuner (60/120 FPS).
 */
@Composable
fun AiEngineerLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val diag = state.crashDiagnosis
    val hw = state.hardwareProfile

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column (52%): AI Crash & Log Analyzer + 1-Click Fix
        Surface(
            modifier = Modifier
                .weight(0.52f)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI CRASH & LOG ANALYZER",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary
                    )
                    Text(
                        text = if (state.isGeminiKeyActive) "Hybrid: Local + Gemini 3.5 Flash" else "Local Deterministic Engine",
                        style = MaterialTheme.typography.labelMedium,
                        color = SplashGold,
                        fontSize = 10.sp
                    )
                }

                // Simulated Crash Triggers for testing 1-Click Remediation
                Text(
                    text = "Протестировать парсер на типовых сбоях latest.log:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.simulateCrashScenarioInLog("JAVA_VERSION") },
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("simulate_java_crash_button")
                    ) {
                        Text("ClassFile 65.0 (Java)", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.simulateCrashScenarioInLog("HEAP_OOM") },
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("simulate_oom_crash_button")
                    ) {
                        Text("OutOfMemoryError", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.simulateCrashScenarioInLog("GL_SHADER") },
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("simulate_gl_crash_button")
                    ) {
                        Text("GL4ES Shader Fault", fontSize = 11.sp)
                    }
                }

                if (diag != null) {
                    val isFault = diag.errorCode != "SESSION_HEALTHY_OR_GENERIC"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                if (isFault) DangerRed else EmeraldPrimary,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "[${diag.errorCode}] ${diag.title}",
                            fontWeight = FontWeight.Bold,
                            color = if (isFault) DangerRed else EmeraldPrimary
                        )
                        Text(
                            text = diag.rootCauseExplanation,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp
                        )
                        Text(
                            text = diag.offendingLogLine,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = SplashGold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0C1014))
                                .padding(6.dp)
                        )
                        if (!diag.aiEnhancedNotes.isNullOrBlank()) {
                            Text(
                                text = "Gemini AI: ${diag.aiEnhancedNotes}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmeraldPrimary,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = { viewModel.applyOneClickCrashFix() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("apply_one_click_fix_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = Color(0xFF041A0C)
                            )
                        ) {
                            Text(
                                text = "Исправить в 1 клик: ${diag.fixAction.buttonLabel}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Right Column (48%): AI Hardware Tuner (RAM, Chipset, G1GC / ZGC for 60/120 FPS)
        Surface(
            modifier = Modifier
                .weight(0.48f)
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
                    text = "AI HARDWARE TUNER (JVM FLAGS & GC)",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )

                if (hw != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        TechSpecRow("Устройство", hw.deviceModel)
                        TechSpecRow("Чипсет / SoC", "${hw.socName} (${hw.primaryAbi})")
                        TechSpecRow("Ядра CPU", "${hw.cpuCores} потоков")
                        TechSpecRow("Системная ОЗУ", "${hw.availableRamMb} МБ своб. / ${hw.totalRamMb} МБ всего")
                        TechSpecRow("Рекомендуемый -Xmx", "${hw.recommendedHeapMb} МБ")
                        TechSpecRow("Сборщик мусора", hw.recommendedGc.title)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.applyAiHardwareTuneProfile(60) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tune_60fps_button")
                        ) {
                            Text("Авто-Тюнинг 60 FPS", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.applyAiHardwareTuneProfile(120) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tune_120fps_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = Color(0xFF041A0C)
                            )
                        ) {
                            Text("Авто-Тюнинг 120 FPS", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    Text(
                        text = "Сформированные флаги JVM:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "-Xmx${state.runtimeConfig.allocatedMemoryMb}M ${state.runtimeConfig.gcAlgorithm.jvmFlag} ${state.runtimeConfig.customJvmFlags}",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = SplashGold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0C1014))
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Настройки среды исполнения PojavLauncher Core: Мульти-рантайм OpenJDK 8/17/21,
 * прослойки GL4ES / VirGL / ANGLE, пул ОЗУ и инспектор ProcessBuilder.
 */
@Composable
fun RuntimeSettingsLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val specPreview = viewModel.getCurrentLaunchSpecPreview()

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column (50%): OpenJDK 8/17/21 & GL4ES/ANGLE/VirGL Selectors
        Surface(
            modifier = Modifier
                .weight(0.50f)
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
                    text = "МУЛЬТИ-РАНТАЙМ OPENJDK (ARM64 / ARMEABI-V7A)",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Авто-выбор Java по эпохе версии игры",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = state.runtimeConfig.autoSelectJava,
                        onCheckedChange = { viewModel.updateRuntimeAutoJava(it) },
                        modifier = Modifier.testTag("auto_select_java_switch")
                    )
                }

                JavaRuntimeMajor.entries.forEach { javaRt ->
                    val isSelected = (state.runtimeConfig.autoSelectJava && state.selectedVersion?.recommendedJava == javaRt) ||
                        (!state.runtimeConfig.autoSelectJava && state.runtimeConfig.selectedJava == javaRt)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { viewModel.updateManualJavaVersion(javaRt) }
                            .padding(10.dp)
                            .testTag("select_java_${javaRt.major}")
                    ) {
                        Text(text = javaRt.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            text = javaRt.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }

                Text(
                    text = "ГРАФИЧЕСКИЙ МОСТ LWJGL / GLFW (ADRENO / MALI)",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )

                MobileGlRenderer.entries.forEach { renderer ->
                    val isSelected = state.runtimeConfig.renderer == renderer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { viewModel.updateGlRenderer(renderer) }
                            .padding(10.dp)
                            .testTag("select_renderer_${renderer.id}")
                    ) {
                        Text(text = "${renderer.title} (${renderer.glVersion})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            text = renderer.gpuTarget,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Right Column (50%): Memory Allocation, GC, Material You Dynamic Color & ProcessBuilder Preview
        Surface(
            modifier = Modifier
                .weight(0.50f)
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
                    text = "ПАМЯТЬ JVM И РЕНДЕРИНГ",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )

                Text(
                    text = "Выделение Java Heap (-Xmx): ${state.runtimeConfig.allocatedMemoryMb} МБ",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                Slider(
                    value = state.runtimeConfig.allocatedMemoryMb.toFloat(),
                    onValueChange = { viewModel.updateAllocatedHeapMb(it.roundToInt()) },
                    valueRange = 1024f..4096f,
                    modifier = Modifier.testTag("heap_memory_slider")
                )

                Text(
                    text = "Масштаб разрешения буфера GLFW: ${state.runtimeConfig.resolutionScalePercent}%",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                Slider(
                    value = state.runtimeConfig.resolutionScalePercent.toFloat(),
                    onValueChange = { viewModel.updateResolutionScale(it.roundToInt()) },
                    valueRange = 50f..100f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GcAlgorithm.entries.forEach { gc ->
                        FilterChip(
                            selected = state.runtimeConfig.gcAlgorithm == gc,
                            onClick = { viewModel.updateGcAlgorithm(gc) },
                            label = { Text(gc.name, fontSize = 11.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Адаптивные цвета Material You (Android 12+)", fontSize = 12.sp)
                    Switch(
                        checked = state.useDynamicMaterialYou,
                        onCheckedChange = { viewModel.toggleDynamicMaterialYou(it) }
                    )
                }

                if (specPreview != null) {
                    Text(
                        text = "ИНСПЕКТОР КОМАНДЫ PROCESSBUILDER",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary
                    )
                    Text(
                        text = specPreview.commandArgs.joinToString(" "),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = Color(0xFFD0D8E0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0C1014))
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}
