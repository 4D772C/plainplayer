package de.plainplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.chooseFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Ink.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Ink.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ink, surface = Panel, primary = Acid, onPrimary = Ink,
                    onBackground = Color(0xFFF0F1EA), onSurface = Color(0xFFF0F1EA),
                    surfaceVariant = Color(0xFF282C26), onSurfaceVariant = Muted,
                    inverseSurface = Color(0xFFE6E8DE), inversePrimary = Color(0xFF4A5A12),
                )
            ) {
                PlayerScreen(viewModel, onPickFolder = { folderPicker.launch(null) })
            }
        }
    }
}
