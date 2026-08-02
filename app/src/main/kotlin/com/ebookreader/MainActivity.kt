package com.ebookreader

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.ebookreader.presentation.AppNavigation
import com.ebookreader.presentation.MainViewModel
import com.ebookreader.presentation.common.EBookReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleViewIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // Handle keep screen on
            val keepScreenOn = uiState.settings.keepScreenOn
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            EBookReaderTheme(appTheme = uiState.settings.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val startDestination = uiState.startDestination
                    if (startDestination != null) {
                        val navController = rememberNavController()
                        AppNavigation(
                            navController = navController,
                            startDestination = startDestination,
                            settings = uiState.settings,
                            onSettingsChange = viewModel::updateSettings,
                            pendingImportUri = uiState.pendingImportUri,
                            onPendingImportHandled = viewModel::consumePendingImport
                        )
                    }
                    // else: show nothing while loading (brief splash)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * The manifest advertises PDF/EPUB VIEW filters, so files can be sent here from a
     * file manager. Without this the app simply opened the library and ignored them.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        try {
            // Keep read access alive past this activity instance where possible.
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not a persistable grant — the one-shot permission is enough to import now.
        }
        viewModel.onFileOpenedFromOutside(uri)
    }
}
