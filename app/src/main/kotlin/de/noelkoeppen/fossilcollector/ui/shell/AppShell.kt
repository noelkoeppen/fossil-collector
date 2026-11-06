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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import de.noelkoeppen.fossilcollector.BuildConfig
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.PrestigeFailure
import de.noelkoeppen.fossilcollector.core.engine.ReconstructionFailure
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.book.BookScreen
import de.noelkoeppen.fossilcollector.ui.dig.DigScreen
import de.noelkoeppen.fossilcollector.ui.fame.FameActions
import de.noelkoeppen.fossilcollector.ui.fame.FameScreen
import de.noelkoeppen.fossilcollector.ui.fame.fameActions
import de.noelkoeppen.fossilcollector.ui.lab.LabActions
import de.noelkoeppen.fossilcollector.ui.lab.LabScreen
import de.noelkoeppen.fossilcollector.ui.lab.labActions
import de.noelkoeppen.fossilcollector.ui.museum.MuseumActions
import de.noelkoeppen.fossilcollector.ui.museum.MuseumScreen
import de.noelkoeppen.fossilcollector.ui.museum.museumActions
import de.noelkoeppen.fossilcollector.ui.GameNumberFormat
import de.noelkoeppen.fossilcollector.ui.feedback.FeedbackTexts
import de.noelkoeppen.fossilcollector.ui.feedback.animatedGameNumber
import de.noelkoeppen.fossilcollector.ui.resolveContentLanguage
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.resolveNumberLanguage
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import de.noelkoeppen.fossilcollector.ui.settings.SettingsActions
import de.noelkoeppen.fossilcollector.ui.settings.SettingsLogic
import de.noelkoeppen.fossilcollector.ui.settings.SettingsScreen
import de.noelkoeppen.fossilcollector.ui.settings.settingsActions
import de.noelkoeppen.fossilcollector.ui.welcome.WelcomeBackDialog
import de.noelkoeppen.fossilcollector.vm.DigActions
import de.noelkoeppen.fossilcollector.vm.GameMessage
import de.noelkoeppen.fossilcollector.vm.GameTab
import de.noelkoeppen.fossilcollector.vm.GameUiState
import de.noelkoeppen.fossilcollector.vm.GameViewModel
import de.noelkoeppen.fossilcollector.vm.TabBadge

/**
 * App-Shell (AP-16, Kapitel 8.1): Theme, obere Leiste, untere Navigation mit den sichtbaren Tabs
 * (Kapitel 8.2) und den Screens in [TabContent] (seit AP-22 alle Tabs). Seit AP-23 zusaetzlich der
 * Willkommen-zurueck-Dialog als Overlay, seit AP-24 der Einstellungs-Screen (Zahnrad, Zurueck schliesst),
 * seit AP-25 Badges, Meilenstein-Toasts, Hinweistexte und Zahlen-Animationen der oberen Leiste.
 */
@Composable
fun AppShell(viewModel: GameViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context by rememberUpdatedState(LocalContext.current)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // Zahlen und Inhaltstexte im Hinweis in Zahlenformat und Sprache des Spielstands.
            val ready = viewModel.uiState.value as? GameUiState.Ready
            val settings = ready?.settings ?: Settings()
            val texts = ready?.texts ?: TextProvider.EMPTY
            val hint = message is GameMessage.TabHint
            snackbarHostState.showSnackbar(
                message = messageText(message, context, settings, texts),
                withDismissAction = hint,
                // Hinweistexte (AP-25) bleiben laenger stehen, alles andere ist ein kurzer Toast.
                duration = if (hint) SnackbarDuration.Long else SnackbarDuration.Short,
            )
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

private fun messageText(
    message: GameMessage,
    context: android.content.Context,
    settings: Settings,
    texts: TextProvider,
): String = when (message) {
    is GameMessage.LoadedFromBackup -> context.getString(R.string.message_loaded_from_backup, message.backupIndex)
    GameMessage.SaveUnreadableNewGame -> context.getString(R.string.message_save_unreadable)
    GameMessage.SaveFailed -> context.getString(R.string.message_save_failed)
    is GameMessage.ReconstructionFailed -> context.getString(
        when (message.reason) {
            ReconstructionFailure.NO_IMPROVEMENT -> R.string.lab_message_recon_no_improvement
            ReconstructionFailure.MISSING_SPARE_ZONES -> R.string.lab_message_recon_missing_zones
            ReconstructionFailure.UNKNOWN_PIECE -> R.string.lab_message_recon_unknown
            ReconstructionFailure.NON_FINITE_RESULT -> R.string.lab_message_recon_error
        },
    )
    is GameMessage.Prestiged -> context.getString(R.string.fame_message_prestiged, numberFormat(context, settings).format(message.fameGained))
    is GameMessage.PrestigeFailed -> context.getString(
        when (message.reason) {
            PrestigeFailure.NOT_ENOUGH_FAME -> R.string.fame_message_prestige_not_enough
            PrestigeFailure.NON_FINITE_RESULT -> R.string.fame_message_prestige_error
        },
    )
    GameMessage.ImportSucceeded -> context.getString(R.string.settings_import_success)
    is GameMessage.ImportFailed -> context.getString(SettingsLogic.importErrorText(message.error))
    GameMessage.SaveDeleted -> context.getString(R.string.settings_deleted)
    GameMessage.SaveDeletedIncomplete -> context.getString(R.string.settings_deleted_incomplete)
    is GameMessage.MilestoneReached -> FeedbackTexts.milestoneText(
        context = context,
        milestoneId = message.milestoneId,
        money = message.money,
        researchPoints = message.researchPoints,
        texts = texts.withLanguage(resolveContentLanguage(settings.language, systemLanguage(context))),
        format = numberFormat(context, settings),
    )
    is GameMessage.TabHint -> context.getString(FeedbackTexts.hintRes(message.tab))
}

private fun systemLanguage(context: android.content.Context): String =
    context.resources.configuration.locales[0]?.language.orEmpty()

/** Zahlenformat fuer Hinweise ausserhalb von Composables (Sprache und Format aus [settings]). */
private fun numberFormat(context: android.content.Context, settings: Settings): GameNumberFormat =
    GameNumberFormat(resolveNumberLanguage(settings.language, systemLanguage(context)), settings.numberFormat)

/**
 * Laufendes Spiel: obere Leiste, Inhalt, untere Navigation.
 *
 * @param viewModel Quelle der Aktionen und Popups fuer die Screens; `null` in Vorschauen (keine Wirkung)
 */
@Composable
fun GameShell(
    state: GameUiState.Ready,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: GameViewModel? = null,
) {
    var selectedTab by rememberSaveable { mutableStateOf(GameTab.DIG) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Ein Tab, der (z. B. nach einem Neustart) nicht sichtbar ist, faellt auf die Grabung zurueck.
    val currentTab = if (selectedTab in state.visibleTabs) selectedTab else GameTab.DIG

    BackHandler(enabled = showSettings) { showSettings = false }

    // Angezeigten Tab fuer die Badges melden (AP-25); Einstellungen offen = kein Tab.
    LaunchedEffect(viewModel, currentTab, showSettings) {
        viewModel?.onTabShown(if (showSettings) null else currentTab)
    }

    Scaffold(
        topBar = {
            TopResourceBar(
                state = state,
                settingsOpen = showSettings,
                onSettingsClick = { showSettings = true },
                onBackClick = { showSettings = false },
            )
        },
        bottomBar = {
            if (!showSettings) {
                GameNavigationBar(
                    visibleTabs = state.visibleTabs,
                    badges = state.badges,
                    selected = currentTab,
                    onSelect = { selectedTab = it },
                )
            }
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
            } else {
                TabContent(currentTab, state, viewModel)
            }
        }
    }

    // Willkommen-zurueck-Dialog (AP-23) als Overlay ueber allem.
    state.welcome?.let { welcome ->
        WelcomeBackDialog(
            state = welcome,
            settings = state.settings,
            texts = state.texts,
            onDismiss = { viewModel?.dismissWelcome() },
        )
    }
}

/** Inhalt eines Tabs. Grabung seit AP-17, Museum seit AP-18, Labor seit AP-19, Sammelbuch seit AP-20, Ruhm seit AP-22. */
@Composable
private fun TabContent(tab: GameTab, state: GameUiState.Ready, viewModel: GameViewModel?) {
    when (tab) {
        GameTab.DIG -> DigScreen(
            state = state,
            actions = viewModel ?: DigActions.NONE,
            findPopups = viewModel?.findPopups ?: emptyFlow(),
        )
        GameTab.LAB -> LabScreen(
            state = state.lab,
            settings = state.settings,
            texts = state.texts,
            actions = remember(viewModel) { viewModel?.let(::labActions) ?: LabActions.NONE },
        )
        GameTab.MUSEUM -> MuseumScreen(
            state = state.museum,
            settings = state.settings,
            texts = state.texts,
            actions = remember(viewModel) { viewModel?.let(::museumActions) ?: MuseumActions.NONE },
        )
        GameTab.BOOK -> BookScreen(
            state = state.book,
            settings = state.settings,
            texts = state.texts,
        )
        GameTab.FAME -> FameScreen(
            state = state.fameScreen,
            settings = state.settings,
            texts = state.texts,
            actions = remember(viewModel) { viewModel?.let(::fameActions) ?: FameActions.NONE },
        )
    }
}

/** Obere Leiste: Geld (+ $/s), FP, Ruhm und Zahnrad (Kapitel 8.1). */
@Composable
private fun TopResourceBar(
    state: GameUiState.Ready,
    settingsOpen: Boolean,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val format = rememberGameNumberFormat(state.settings)
    // Weiche Zahlen-Animation (AP-25), bei grossen Spruengen und Kaeufen sofort.
    val money = animatedGameNumber(state.money)
    val researchPoints = animatedGameNumber(state.researchPoints)
    val fame = animatedGameNumber(state.fame)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (settingsOpen) {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
                ResourceValue(
                    label = stringResource(R.string.top_bar_money_label),
                    value = stringResource(R.string.top_bar_money_value, format.format(money)),
                    detail = stringResource(R.string.top_bar_money_rate, format.format(state.moneyPerSecond)),
                    modifier = Modifier.weight(1.4f),
                )
                ResourceValue(
                    label = stringResource(R.string.top_bar_research_label),
                    value = format.format(researchPoints),
                    modifier = Modifier.weight(1f),
                )
                ResourceValue(
                    label = stringResource(R.string.top_bar_fame_label),
                    value = format.format(fame),
                    modifier = Modifier.weight(1f),
                )
                if (!settingsOpen) {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.top_bar_settings))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ResourceValue(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Untere Navigation mit den sichtbaren Tabs (Kapitel 8.1 und 8.2) und Badges (AP-25): Ereignisse
 * (Forschung fertig, neuer Bucheintrag, Prestige verfuegbar) als roter Punkt, "Kauf moeglich" als
 * dezenter Punkt in der Akzentfarbe.
 */
@Composable
private fun GameNavigationBar(
    visibleTabs: List<GameTab>,
    badges: Map<GameTab, TabBadge>,
    selected: GameTab,
    onSelect: (GameTab) -> Unit,
) {
    NavigationBar {
        for (tab in visibleTabs) {
            val label = stringResource(tab.titleRes)
            val badge = badges[tab]
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (badge != null) {
                                val description = stringResource(FeedbackTexts.badgeDescriptionRes(badge))
                                Badge(
                                    modifier = Modifier.semantics { contentDescription = description },
                                    containerColor = if (badge == TabBadge.AFFORDABLE) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        },
                    ) {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** Platzhalter fuer einen noch nicht gebauten Screen: Titel und kurzer Text. */
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

private val GameTab.titleRes: Int
    get() = when (this) {
        GameTab.DIG -> R.string.tab_dig
        GameTab.LAB -> R.string.tab_lab
        GameTab.MUSEUM -> R.string.tab_museum
        GameTab.BOOK -> R.string.tab_book
        GameTab.FAME -> R.string.tab_fame
    }

/** Vorlaeufige Material-Symbole; eigene Icons folgen in AP-26. */
private val GameTab.icon: ImageVector
    get() = when (this) {
        GameTab.DIG -> Icons.Filled.Build
        GameTab.LAB -> Icons.Filled.Search
        GameTab.MUSEUM -> Icons.Filled.Home
        GameTab.BOOK -> Icons.AutoMirrored.Filled.List
        GameTab.FAME -> Icons.Filled.Star
    }

@Preview
@Composable
private fun GameShellPreview() {
    FossilCollectorTheme {
        GameShell(
            GameUiState.Ready(
                money = GameNumber(12_345.0),
                moneyPerSecond = GameNumber(42.5),
                researchPoints = GameNumber(7.0),
                fame = GameNumber.ZERO,
                visibleTabs = GameTab.entries.toList(),
                settings = Settings(),
                qualityTiers = emptyList(),
            ),
        )
    }
}
