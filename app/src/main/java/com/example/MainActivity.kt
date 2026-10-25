package com.example

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.repository.CloudLaunchDatabaseRepository
import com.example.ui.AccountsAuthLandscapeScreen
import com.example.ui.AiEngineerLandscapeScreen
import com.example.ui.GameSessionLandscapeScreen
import com.example.ui.GoogleAuthGateScreen
import com.example.ui.PlayHubLandscapeScreen
import com.example.ui.RuntimeSettingsLandscapeScreen
import com.example.ui.SkinStudioLandscapeScreen
import com.example.ui.TouchControllerStudioScreen
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        setContent {
            val auth = remember { Firebase.auth }
            var currentUser by remember { mutableStateOf(auth.currentUser) }
            var localOfflineBypass by remember { mutableStateOf(false) }

            DisposableEffect(auth) {
                val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                    currentUser = firebaseAuth.currentUser
                }
                auth.addAuthStateListener(listener)
                onDispose {
                    auth.removeAuthStateListener(listener)
                }
            }

            if (currentUser == null && !localOfflineBypass) {
                MyApplicationTheme(useDynamicColor = false) {
                    GoogleAuthGateScreen(
                        onAuthSuccess = {
                            currentUser = Firebase.auth.currentUser
                        },
                        onContinueOfflineLocal = {
                            localOfflineBypass = true
                        }
                    )
                }
            } else {
                val activeUid = currentUser?.uid ?: "local_offline_user"
                val appContext = applicationContext
                val factory: ViewModelProvider.Factory = remember(activeUid) {
                    viewModelFactory {
                        initializer {
                            val databaseId = appContext.getString(R.string.firestore_database_id)
                            val firestore = FirebaseFirestore.getInstance(databaseId)
                            val repository = CloudLaunchDatabaseRepository(firestore)
                            LauncherViewModel(
                                context = appContext,
                                cloudRepository = repository,
                                currentUserId = activeUid
                            )
                        }
                    }
                }
                val launcherViewModel: LauncherViewModel = viewModel(
                    key = "monolith_vm_$activeUid",
                    factory = factory
                )
                val state by launcherViewModel.uiState.collectAsStateWithLifecycle()
                val view = LocalView.current

                LaunchedEffect(state.currentDestination) {
                    val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (state.currentDestination == LauncherDestination.GAME_SESSION) {
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                MyApplicationTheme(useDynamicColor = state.useDynamicMaterialYou) {
                    MonolithLandscapeAppShell(
                        state = state,
                        viewModel = launcherViewModel
                    )
                }
            }
        }
    }
}

@Composable
fun MonolithLandscapeAppShell(
    state: LauncherUiState,
    viewModel: LauncherViewModel
) {
    // When playing Minecraft Java (GAME_SESSION), completely exit the launcher UI shell
    // and render the standalone full-screen 3D OpenGL ES game window.
    if (state.currentDestination == LauncherDestination.GAME_SESSION) {
        BackHandler {
            viewModel.togglePauseMenuModal(!state.showPauseMenuModal)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            GameSessionLandscapeScreen(state = state, viewModel = viewModel)
        }
        return
    }

    // Handle system Back press on secondary launcher screens
    BackHandler(enabled = state.currentDestination != LauncherDestination.PLAY_HUB) {
        viewModel.navigateTo(LauncherDestination.PLAY_HUB)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // Permanent Left NavigationRail for Landscape Launcher Ergonomics
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                header = {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp, bottom = 4.dp)
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0E151B))
                            .border(1.dp, EmeraldPrimary.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "Monolith Icon",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outline)
                    .testTag("landscape_navigation_rail")
            ) {
                LauncherDestination.entries.forEach { dest ->
                    val iconRes = when (dest) {
                        LauncherDestination.PLAY_HUB -> R.drawable.ic_play
                        LauncherDestination.GAME_SESSION -> R.drawable.ic_console
                        LauncherDestination.TOUCH_CONTROLLER -> R.drawable.ic_gamepad
                        LauncherDestination.SKIN_STUDIO -> R.drawable.ic_skin_layers
                        LauncherDestination.ACCOUNTS_AUTH -> R.drawable.ic_profile
                        LauncherDestination.AI_ENGINEER -> R.drawable.ic_ai_spark
                        LauncherDestination.RUNTIME_SETTINGS -> R.drawable.ic_settings
                    }

                    NavigationRailItem(
                        selected = state.currentDestination == dest,
                        onClick = { viewModel.navigateTo(dest) },
                        icon = {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = dest.title,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = dest.title,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color(0xFF061E0F),
                            selectedTextColor = EmeraldPrimary,
                            indicatorColor = EmeraldPrimary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.testTag("nav_rail_${dest.name.lowercase()}")
                    )
                }
            }

            // Main Two-Pane Workspace Area
            Crossfade(
                targetState = state.currentDestination,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                label = "workspace_crossfade"
            ) { destination ->
                when (destination) {
                    LauncherDestination.PLAY_HUB -> PlayHubLandscapeScreen(state, viewModel)
                    LauncherDestination.GAME_SESSION -> GameSessionLandscapeScreen(state, viewModel)
                    LauncherDestination.TOUCH_CONTROLLER -> TouchControllerStudioScreen(state, viewModel)
                    LauncherDestination.SKIN_STUDIO -> SkinStudioLandscapeScreen(state, viewModel)
                    LauncherDestination.ACCOUNTS_AUTH -> AccountsAuthLandscapeScreen(state, viewModel)
                    LauncherDestination.AI_ENGINEER -> AiEngineerLandscapeScreen(state, viewModel)
                    LauncherDestination.RUNTIME_SETTINGS -> RuntimeSettingsLandscapeScreen(state, viewModel)
                }
            }
        }
    }
}
