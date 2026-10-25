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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.example.LauncherUiState
import com.example.LauncherViewModel
import com.example.R
import com.example.auth.AccountMode
import com.example.pojavruntime.GcAlgorithm
import com.example.pojavruntime.JavaRuntimeMajor
import com.example.pojavruntime.LaunchCheckSeverity
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
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
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
 * Двойной селектор аккаунтов: Официальный Microsoft / Xbox Live OAuth2 и Offline Mode (UUID v3) +
 * Статус облачной синхронизации Firebase Auth (Google Sign-In).
 */
@Composable
fun AccountsAuthLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val firebaseUser = remember { Firebase.auth.currentUser }
    var selectedAuthTab by remember { mutableStateOf(AccountMode.OFFLINE_LOCAL) }
    var offlineNickInput by remember { mutableStateOf("VanillaBuilder") }
    var msaCodeInput by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column (48%): Saved Profiles List + Cloud Auth Status
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

                // Cloud Google Sign-In Status Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, EmeraldPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "ОБЛАЧНАЯ БАЗА ДАННЫХ FIREBASE (GOOGLE AUTH)",
                        style = MaterialTheme.typography.labelMedium,
                        color = SplashGold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Аккаунт: ${firebaseUser?.email ?: firebaseUser?.displayName ?: firebaseUser?.uid ?: "Authorized"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 11.sp
                    )
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                runCatching {
                                    CredentialManager.create(context)
                                        .clearCredentialState(ClearCredentialStateRequest())
                                }
                                Firebase.auth.signOut()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .testTag("google_cloud_sign_out_button")
                    ) {
                        Text("Сменить Google аккаунт облачной БД", fontSize = 11.sp)
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
 * Практичные AI-функции:
 * 1) Авто-инспектор и авто-исправление 8 критических ошибок запуска (PreLaunchDiagnosticInspector)
 * 2) AI Crash & Log Analyzer (с исправлением в 1 клик)
 * 3) AI Hardware Tuner (60/120 FPS)
 */
@Composable
fun AiEngineerLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val diag = state.crashDiagnosis
    val hw = state.hardwareProfile
    val inspection = state.preLaunchInspection

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column (54%): Pre-Launch 8-Error Auto-Repair Inspector + AI Crash & Log Analyzer
        Surface(
            modifier = Modifier
                .weight(0.54f)
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
                        text = "ИНСПЕКТОР И АВТО-ИСПРАВЛЕНИЕ ОШИБОК ЗАПУСКА",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary
                    )
                    Text(
                        text = "ARM64 + Firestore Sync",
                        style = MaterialTheme.typography.labelMedium,
                        color = SplashGold,
                        fontSize = 10.sp
                    )
                }

                Button(
                    onClick = { viewModel.runPreLaunchDiagnosticAndRepair() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_prelaunch_diagnostic_repair_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = Color(0xFF041A0C)
                    )
                ) {
                    Text(
                        text = "Проверить и устранить причины сбоя запуска (8 узлов)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                if (inspection != null) {
                    val hasCritical = inspection.checks.any { it.severity == LaunchCheckSeverity.CRITICAL_WARNING }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                if (hasCritical) DangerRed else EmeraldPrimary,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Версия ${inspection.versionId}: авто-исправлено ${inspection.repairedIssuesCount} | статус: ${inspection.overallStatus} | RAM лимит: ${inspection.recommendedHeapMb} МБ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (hasCritical) DangerRed else EmeraldPrimary
                        )
                        inspection.checks.forEach { item ->
                            val badgeColor = when (item.severity) {
                                LaunchCheckSeverity.OK -> EmeraldPrimary
                                LaunchCheckSeverity.AUTO_REPAIRED -> SplashGold
                                LaunchCheckSeverity.CRITICAL_WARNING -> DangerRed
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0E141B))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = item.severity.name,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 9.sp,
                                        color = badgeColor
                                    )
                                }
                                Text(
                                    text = item.details,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Действие: ${item.actionTaken}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 9.sp,
                                    color = badgeColor
                                )
                            }
                        }
                    }
                }

                // Real latest.log Disk Reader & Analyzer
                Text(
                    text = "AI CRASH & LOG ANALYZER (logs/latest.log):",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )
                OutlinedButton(
                    onClick = { viewModel.analyzeLatestLogFileFromDisk() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .testTag("analyze_real_latest_log_button")
                ) {
                    Text("Просканировать logs/latest.log с диска", fontSize = 12.sp)
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

        // Right Column (46%): AI Hardware Tuner (RAM, Chipset, G1GC / ZGC for 60/120 FPS)
        Surface(
            modifier = Modifier
                .weight(0.46f)
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
 * прослойки GL4ES / VirGL / ANGLE, пул ОЗУ, инспектор ProcessBuilder и
 * Облачная база данных профилей Firebase Firestore.
 */
@Composable
fun RuntimeSettingsLandscapeScreen(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier
) {
    val specPreview = viewModel.getCurrentLaunchSpecPreview()
    var cloudProfileTitleInput by remember {
        mutableStateOf("ARM64 ${state.selectedVersion?.id ?: "1.21.4"} Turbo")
    }

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

        // Right Column (50%): Memory Allocation, Cloud Firestore DB Profiles & ProcessBuilder Preview
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
                    text = "ОБЛАЧНАЯ БАЗА ДАННЫХ ПРОФИЛЕЙ (FIRESTORE)",
                    style = MaterialTheme.typography.labelMedium,
                    color = SplashGold
                )

                Text(
                    text = state.cloudSyncStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmeraldPrimary,
                    fontSize = 11.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = cloudProfileTitleInput,
                        onValueChange = { cloudProfileTitleInput = it },
                        label = { Text("Название облачного профиля", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cloud_profile_title_input")
                    )
                    Button(
                        onClick = { viewModel.saveCurrentLaunchProfileToCloud(cloudProfileTitleInput) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = Color(0xFF041A0C)
                        ),
                        modifier = Modifier.testTag("save_cloud_profile_button")
                    ) {
                        Text("Сохранить в БД", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                if (state.cloudLaunchProfiles.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.cloudLaunchProfiles.forEach { profile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.applyCloudLaunchProfile(profile) }
                                    .padding(8.dp)
                                    .testTag("cloud_profile_item_${profile.id}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${profile.profileName} (${profile.versionId})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Java ${profile.javaMajor} | ${profile.allocatedMemoryMb}MB | ${profile.gcAlgorithm} | ${profile.rendererId}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deleteCloudLaunchProfile(profile.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_trash),
                                        contentDescription = "Удалить профиль из БД",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.cloudDiagnosticReports.isNotEmpty()) {
                    val latestReport = state.cloudDiagnosticReports.first()
                    Text(
                        text = "Последний отчет БД: ${latestReport.summary} (исправлено: ${latestReport.repairedIssuesCount})",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = SplashGold
                    )
                }

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
