package vn.mangatranslate.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.mangatranslate.app.ui.screens.AutoTranslateScreen
import vn.mangatranslate.app.ui.screens.ChatGptTestScreen
import vn.mangatranslate.app.ui.screens.DebugScreen
import vn.mangatranslate.app.ui.screens.HomeScreen
import vn.mangatranslate.app.ui.screens.MtTestScreen
import vn.mangatranslate.app.ui.screens.ReaderScreen
import vn.mangatranslate.app.ui.screens.SettingsScreen
import vn.mangatranslate.app.ui.theme.MangaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MangaTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (state.screen) {
        Screen.HOME -> HomeScreen(state, vm)
        Screen.READER -> ReaderScreen(state, vm)
        Screen.SETTINGS -> SettingsScreen(state, vm)
        Screen.DEBUG -> DebugScreen(state, vm)
        Screen.MT_TEST -> MtTestScreen(state, vm)
        Screen.CHATGPT -> ChatGptTestScreen(vm)
        Screen.AUTO -> AutoTranslateScreen(vm)
    }
}
