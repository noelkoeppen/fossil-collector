package de.noelkoeppen.fossilcollector.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import de.noelkoeppen.fossilcollector.BuildConfig
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.ui.theme.AppIcons
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import de.noelkoeppen.fossilcollector.ui.settings.SettingsActions
import de.noelkoeppen.fossilcollector.ui.settings.SettingsLogic
import de.noelkoeppen.fossilcollector.ui.settings.SettingsScreen
import de.noelkoeppen.fossilcollector.ui.settings.settingsActions
import de.noelkoeppen.fossilcollector.vm.GameMessage
import de.noelkoeppen.fossilcollector.vm.GameUiState
import de.noelkoeppen.fossilcollector.vm.GameViewModel

/**
 * App-Shell: Theme, Ladeanzeige bzw. Fehlerbild der Spieldaten, danach der Hauptbildschirm mit
 * oberer Leiste (Titel, Zahnrad) und dem Einstellungs-Screen (Zurueck schliesst ihn).
 *
 * Nach AP2-02 ist der Hauptbildschirm leer; die Screens des neuen Designs kommen mit den
 * folgenden Arbeitspaketen dazu.
 */
@Composable
fun AppShell(viewModel: GameViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context by rememberUpdatedState(LocalContext.current)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message = messageText(message, context))
        }
    }

    val qualityColors = (uiState as? GameUiState.Ready)?.qualityTiers
    val colors = remember(qualityColors) { qualityColors?.let(QualityColors::fromTiers) ?: QualityColors.EMPTY }
    FossilCollectorTheme(qualityColors = colors) {
        when (val state = uiState) {
            GameUiState.Loading -> LoadingScreen()
            GameUiState.DataError -> DataErrorScreen()
            is GameUiState.Ready -> GameShell(state, snackbarHostState, viewModel)
        }
    }
}

private fun messageText(message: GameMessage, context: android.content.Context): String = when (message) {
    is GameMessage.LoadedFromBackup -> context.getString(R.string.message_loaded_from_backup, message.backupIndex)
    GameMessage.SaveUnreadableNewGame -> context.getString(R.string.message_save_unreadable)
    GameMessage.OldSaveDiscarded -> context.getString(R.string.message_old_save_discarded)
    GameMessage.SaveFailed -> context.getString(R.string.message_save_failed)
    GameMessage.ImportSucceeded -> context.getString(R.string.settings_import_success)
    is GameMessage.ImportFailed -> context.getString(SettingsLogic.importErrorText(message.error))
    GameMessage.SaveDeleted -> context.getString(R.string.settings_deleted)
    GameMessage.SaveDeletedIncomplete -> context.getString(R.string.settings_deleted_incomplete)
}

/**
 * Geladenes Spiel: obere Leiste und Inhalt (leerer Hauptbildschirm oder Einstellungen).
 *
 * @param viewModel Quelle der Aktionen; `null` in Vorschauen (keine Wirkung)
 */
@Composable
fun GameShell(
    state: GameUiState.Ready,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: GameViewModel? = null,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        topBar = {
            TopBar(
                settingsOpen = showSettings,
                onSettingsClick = { showSettings = true },
                onBackClick = { showSettings = false },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showSettings) {
                SettingsScreen(
                    settings = state.settings,
                    versionName = BuildConfig.VERSION_NAME,
                    actions = remember(viewModel) { viewModel?.let(::settingsActions) ?: SettingsActions.NONE },
                    onMessage = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
                )
            }
        }
    }
}

/** Obere Leiste: App-Name und Zahnrad bzw. Zurueck, solange die Einstellungen offen sind. */
@Composable
private fun TopBar(
    settingsOpen: Boolean,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (settingsOpen) {
                    IconButton(onClick = onBackClick) {
                        Icon(painterResource(AppIcons.Back), contentDescription = stringResource(R.string.back))
                    }
                }
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (!settingsOpen) {
                    IconButton(onClick = onSettingsClick) {
                        Icon(painterResource(AppIcons.Settings), contentDescription = stringResource(R.string.top_bar_settings))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Platzhalter mit Titel und kurzem Text (z. B. Fehlerbild der Spieldaten). */
@Composable
fun PlaceholderScreen(title: String, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DataErrorScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        PlaceholderScreen(
            title = stringResource(R.string.data_error_title),
            text = stringResource(R.string.data_error_text),
        )
    }
}

@Preview
@Composable
private fun GameShellPreview() {
    FossilCollectorTheme {
        GameShell(
            GameUiState.Ready(
                settings = Settings(),
                qualityTiers = emptyList(),
            ),
        )
    }
}
