package com.ultrapg.luna

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import com.ultrapg.luna.assistant.AssistantRoleHelper
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.ui.screens.AiSettingsScreen
import com.ultrapg.luna.ui.screens.ChatsScreen
import com.ultrapg.luna.ui.screens.ModelsScreen
import com.ultrapg.luna.ui.screens.OverlayConfigScreen
import com.ultrapg.luna.ui.screens.ToolsMcpScreen
import com.ultrapg.luna.ui.theme.LunaTheme

enum class LunaTab(
    val labelEn: String,
    val labelDe: String,
    val icon: ImageVector
) {
    CHATS("Chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    BUBBLE("Pill", "Pill", Icons.Default.SmartButton),
    AI_NPU("AI", "KI", Icons.Default.Psychology),
    VOICE_MODELS("Voice", "Stimme", Icons.Default.GraphicEq),
    TOOLS_MCP("Tools", "Tools", Icons.Default.Extension)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LunaSettingsRepository.init(applicationContext)

        setContent {
            LunaTheme {
                val settings by LunaSettingsRepository.settingsFlow.collectAsState()
                val isGerman = settings.appLanguage.isGerman

                var currentTab by remember { mutableStateOf(LunaTab.CHATS) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    val needed = mutableListOf<String>()
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        needed += Manifest.permission.RECORD_AUDIO
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        needed += Manifest.permission.POST_NOTIFICATIONS
                    }
                    if (needed.isNotEmpty()) {
                        permissionLauncher.launch(needed.toTypedArray())
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            LunaTab.entries.forEach { tab ->
                                val label = if (isGerman) tab.labelDe else tab.labelEn
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            LunaTab.CHATS -> ChatsScreen(
                                onRequestMicPermission = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) {
                                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                    }
                                    granted
                                }
                            )
                            LunaTab.BUBBLE -> OverlayConfigScreen(
                                onRequestAssistantRole = {
                                    AssistantRoleHelper.openDefaultAssistantSettings(this@MainActivity)
                                }
                            )
                            LunaTab.AI_NPU -> AiSettingsScreen()
                            LunaTab.VOICE_MODELS -> ModelsScreen()
                            LunaTab.TOOLS_MCP -> ToolsMcpScreen()
                        }
                    }
                }
            }
        }
    }
}
