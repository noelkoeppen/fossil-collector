package de.noelkoeppen.fossilcollector.vm

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.DataValidationException
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.AdvanceResult
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.GameEvent
import de.noelkoeppen.fossilcollector.core.engine.NumericErrorHandler
import de.noelkoeppen.fossilcollector.core.engine.PrestigeMetaStep
import de.noelkoeppen.fossilcollector.core.engine.PrestigeResult
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.engine.ReconstructionResult
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.core.save.SaveException
import de.noelkoeppen.fossilcollector.platform.SaveLoadResult
import de.noelkoeppen.fossilcollector.platform.SaveSource
import de.noelkoeppen.fossilcollector.platform.SaveStorage
import de.noelkoeppen.fossilcollector.platform.TimeSource
import de.noelkoeppen.fossilcollector.ui.book.BookUiMapper
import de.noelkoeppen.fossilcollector.ui.book.BookUiState
import de.noelkoeppen.fossilcollector.ui.fame.FameUiMapper
import de.noelkoeppen.fossilcollector.ui.fame.FameUiState
import de.noelkoeppen.fossilcollector.ui.fame.PrestigeDialogState
import de.noelkoeppen.fossilcollector.ui.lab.LabUiMapper
import de.noelkoeppen.fossilcollector.ui.museum.MuseumUiMapper
import de.noelkoeppen.fossilcollector.ui.welcome.WelcomeUiMapper
import de.noelkoeppen.fossilcollector.ui.welcome.WelcomeUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Spielschleife und Bindeglied zwischen `core` und UI (GAME_DESIGN.md, Kapitel 10.3).
 *
 * - **Laden:** Spieldaten ueber [loadData] (Standard: [DataRepository] aus den Klassenpfad-Ressourcen
 *   von `core`, die im APK liegen), dann den Spielstand ueber [SaveStorage]. Ohne Spielstand oder
 *   wenn keiner lesbar ist: neues Spiel ueber `GameState.newGame` + [RunStart.values].
 * - **Offline-Fortschritt (AP-23, Kapitel 7.1):** Jedes Mal, wenn die App in den Vordergrund kommt
 *   (auch beim ersten Start nach dem Laden), rechnet [catchUpOffline] die Zeit seit `lastSavedAt`
 *   ueber `GameEngine.applyOffline` nach, bevor die Spielschleife startet. Die Schleife ist dabei
 *   pausiert (sie laeuft im selben `collectLatest`-Block erst danach). Gerechnet wird auf
 *   [gameDispatcher] (`Dispatchers.Default` mit Parallelitaet 1, nie der Main-Thread), damit Engine
 *   und Spielstand nur von einem Thread benutzt werden. Danach wird der Zustand uebernommen und
 *   gespeichert. Ab [WelcomeUiMapper.MIN_DIALOG_SECONDS] erscheint der Willkommen-Dialog
 *   (waehrend der Berechnung mit Lade-Indikator), kuerzere Abwesenheiten werden ohne Dialog angerechnet.
 * - **Schleife:** 10 Ticks/s, je Tick `GameEngine.advance(state, 0.1, rng)`. Danach wird ein
 *   unveraenderlicher [GameUiState] per [uiState] veroeffentlicht, Ereignisse gehen an [events].
 *   Die Schleife laeuft nur im Vordergrund (siehe [onAppForeground]/[onAppBackground]).
 * - **Speichern:** Autosave alle [AUTOSAVE_INTERVAL_NANOS], beim Wechsel in den Hintergrund
 *   (Prozess-Lifecycle `ON_STOP` ueber `platform/AppLifecycleObserver` und `Activity.onStop`) sowie
 *   direkt nach dem Anlegen eines neuen Spiels und direkt nach dem Prestige (Kapitel 7.2). `lastSavedAt` wird beim Speichern auf die aktuelle Zeit gesetzt.
 * - **Einstellungen (AP-24, Kapitel 8.3):** Sprache und Zahlenformat aendern den Spielstand und
 *   werden sofort gespeichert. Export, Import und "Spielstand loeschen" laufen ueber [SaveTransfer];
 *   ein importierter bzw. neuer Stand ersetzt den aktuellen ueber [replaceState] (Modifier-Abgleich,
 *   UI-Zwischenstaende zuruecksetzen, sofort speichern).
 * - **Feedback (AP-25, Kapitel 5.2, 8.1, 8.2):** Badges der Tabs ([TabBadges], "seit letztem
 *   Besuch" als [UnseenUpdates] nur im Speicher, gemeldet ueber [onTabShown]), Meilenstein-Toasts
 *   ([GameMessage.MilestoneReached]) und Hinweistexte beim ersten Erscheinen eines Tabs
 *   ([GameMessage.TabHint], gezeigte Tabs dauerhaft in `meta.revealedTabs`, [TabReveal]).
 * - **Kaeufe** laufen einheitlich ueber [purchase] mit den Kauf-Funktionen der [GameEngine].
 *
 * Threading: Spielstand, Engine und [ModifierEngine] werden ausschliesslich auf [gameDispatcher]
 * (Parallelitaet 1) angefasst. Dateizugriffe laufen auf [ioDispatcher] unter einem Mutex.
 *
 * Spiellogik steht nur in `core`; dieses ViewModel ruft ausschliesslich Engine-Funktionen auf.
 *
 * @param storage Spielstand-Dateien
 * @param time Zeitquelle
 * @param numericErrorHandler Reaktion auf `NaN`/`Infinity` in `advance` (Debug: werfen, Release: loggen)
 * @param loadData laedt und validiert die Spieldaten
 * @param rng Zufallsquelle der Engine
 */
class GameViewModel(
    private val storage: SaveStorage,
    private val time: TimeSource = TimeSource.System,
    private val numericErrorHandler: NumericErrorHandler = NumericErrorHandler.THROW,
    private val loadData: () -> GameData = { DataRepository().load() },
    private val rng: GameRandom = SeededRandom(time.currentTimeMillis()),
    private val gameDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(), DigActions {

    private val _uiState = MutableStateFlow<GameUiState>(GameUiState.Loading)

    /** Aktueller UI-Zustand (unveraenderlich). */
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Ereignisse aus `advance()` (Funde, Forschung fertig ...) fuer Popups und Badges. Heisser
     * Flow ohne Wiederholung: Ereignisse ohne aktiven Sammler gehen verloren (der Zustand selbst
     * steckt immer vollstaendig in [uiState]).
     */
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val messageChannel = Channel<GameMessage>(Channel.BUFFERED)

    /** Einmalige Hinweise (Backup geladen, Speichern fehlgeschlagen ...); werden genau einmal zugestellt. */
    val messages: Flow<GameMessage> = messageChannel.receiveAsFlow()

    private val _findPopups = MutableSharedFlow<FindPopup>(
        extraBufferCapacity = POPUP_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Fund-Popups fuer den Screen Grabung (Kapitel 8.3), bereits gebuendelt ([FindPopupBundler]:
     * hoechstens eines pro Sekunde, bessere Stufe zuerst). Heisser Flow ohne Wiederholung.
     */
    val findPopups: SharedFlow<FindPopup> = _findPopups.asSharedFlow()

    // --- Nur auf gameDispatcher ---
    private var data: GameData? = null
    private var engine: GameEngine? = null
    private var state: GameState? = null
    private var lastSavedState: GameState? = null
    private var revealedTabs: Set<GameTab> = emptySet()
    private var activeTab: GameTab? = null
    private var unseen: UnseenUpdates = UnseenUpdates.NONE
    private val moneyRate = RateTracker()
    private var purchases: Purchases? = null
    private var qualityTiers: QualityTiers? = null
    private var texts: TextProvider = TextProvider.EMPTY
    private var bookMapper: BookUiMapper? = null
    private var prestigeDialog: PrestigeDialogState? = null
    private var welcome: WelcomeUiState? = null
    private var transfer: SaveTransfer? = null
    private val popupBundler = FindPopupBundler()

    private val foreground = MutableStateFlow(false)
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch(gameDispatcher) {
            if (!load()) return@launch
            foreground.collectLatest { active ->
                if (active) {
                    catchUpOffline()
                    runLoop()
                }
            }
        }
    }

    /** App ist sichtbar: Offline-Zeit nachrechnen, dann laeuft die Spielschleife (idempotent). */
    fun onAppForeground() {
        foreground.value = true
    }

    /** App geht in den Hintergrund: Schleife anhalten und speichern (idempotent). */
    fun onAppBackground() {
        // Nur beim Uebergang Vordergrund -> Hintergrund wird `lastSavedAt` in jedem Fall neu gesetzt
        // (auch ohne Aenderung seit dem letzten Speichern), damit die Offline-Zeit genau hier beginnt
        // und keine Vordergrund-Zeit doppelt zaehlt. Der zweite Aufruf (Activity und Prozess melden
        // beide) aendert dann nichts mehr.
        val wasForeground = foreground.value
        foreground.value = false
        // NonCancellable: Das Speichern soll auch dann fertig werden, wenn das ViewModel gerade
        // geraeumt wird (Activity beendet).
        viewModelScope.launch(gameDispatcher + NonCancellable) { saveCurrent(force = wasForeground) }
    }

    /** Schliesst den Willkommen-zurueck-Dialog; waehrend der Berechnung ohne Wirkung. */
    fun dismissWelcome() {
        viewModelScope.launch(gameDispatcher) {
            if (welcome is WelcomeUiState.Summary) {
                welcome = null
                publish()
            }
        }
    }

    /**
     * Fuehrt eine Spieleraktion aus (Erweiterungsstelle fuer AP-17 ff.): [action] bekommt Engine und
     * aktuellen Zustand und gibt den neuen Zustand zurueck, z. B. `{ e, s -> e.tapDig(s, layerId) }`.
     * Laeuft auf dem Spiel-Thread; ohne geladenes Spiel passiert nichts.
     */
    fun perform(action: (GameEngine, GameState) -> GameState) {
        viewModelScope.launch(gameDispatcher) {
            val currentEngine = engine ?: return@launch
            val current = state ?: return@launch
            val newState = action(currentEngine, current)
            state = newState
            // Kaeufe aendern ggf. Modifier-Quellen (z. B. Praeparatoren); Abfragen der UI sollen sie sofort sehen.
            if (newState !== current) currentEngine.syncModifiers(newState)
            publish()
        }
    }

    /**
     * Wie [perform], fuer Aktionen mit Ereignissen (z. B. `tapPrep`): Die Ereignisse gehen wie die
     * aus `advance` an [events] und an die Fund-Popups.
     */
    fun performWithEvents(action: (GameEngine, GameState) -> AdvanceResult) {
        viewModelScope.launch(gameDispatcher) {
            val currentEngine = engine ?: return@launch
            val current = state ?: return@launch
            val result = action(currentEngine, current)
            state = result.state
            if (result.state !== current) currentEngine.syncModifiers(result.state)
            dispatchEvents(result.events)
            publish()
        }
    }

    // --- Aktionen des Screens Grabung (AP-17) ---

    override fun tapDig(layerId: String) = perform { engine, state -> engine.tapDig(state, layerId) }

    override fun tapPrep() = performWithEvents { engine, state -> engine.tapPrep(state, rng) }

    override fun buyTeams(layerId: String, mode: BuyMode) = purchase { engine, state -> engine.buyTeams(state, layerId, mode) }

    override fun buyPreparators(mode: BuyMode) = purchase { engine, state -> engine.buyPreparators(state, mode) }

    override fun unlockLayer(layerId: String) = purchase { engine, state -> engine.unlockLayer(state, layerId) }

    // --- Feedback (AP-25) ---

    /**
     * Die UI meldet den gerade angezeigten Tab (`null`: keiner, z. B. Einstellungen offen). Ein
     * besuchter Tab verliert sein Badge "Forschung fertig" bzw. "neuer Bucheintrag"; solange er
     * offen ist, bekommt er keine Badges.
     */
    fun onTabShown(tab: GameTab?) {
        viewModelScope.launch(gameDispatcher) {
            activeTab = tab
            unseen = unseen.visited(tab)
            publish()
        }
    }

    // --- Aktionen des Screens Labor (AP-19) ---

    /**
     * Rekonstruiert [pieceKey] ueber `GameEngine.reconstruct`. Bei Erfolg gehen die Ereignisse
     * (Rekonstruiert, Buch, Fund ...) wie bei [performWithEvents] an [events] und die Fund-Popups.
     * Wird die Aktion abgelehnt (z. B. weil sich der Spielstand seit der Anzeige geaendert hat und
     * sie keine Verbesserung mehr waere), bleibt der Zustand unveraendert und [messages] liefert
     * [GameMessage.ReconstructionFailed].
     */
    fun reconstruct(pieceKey: PieceKey) = performWithEvents { engine, current ->
        when (val result = engine.reconstruct(current, pieceKey)) {
            is ReconstructionResult.Success -> AdvanceResult(result.state, result.events)
            is ReconstructionResult.Failure -> {
                messageChannel.trySend(GameMessage.ReconstructionFailed(result.reason))
                AdvanceResult(current, emptyList())
            }
        }
    }

    // --- Ruhm-Screen: Prestige-Dialog (AP-22) ---

    /** Oeffnet den Prestige-Dialog ("Skills behalten" vorgewaehlt), sofern die Vorschau mindestens 1 Ruhm zeigt. */
    fun openPrestigeDialog() = updatePrestigeDialog { engine, current, _ ->
        if (engine.canPrestige(current)) PrestigeDialogState() else null
    }

    /** Schliesst den Prestige-Dialog ohne Prestige; die Auswahl verfaellt. */
    fun dismissPrestigeDialog() = updatePrestigeDialog { _, _, _ -> null }

    /** Wahl "Skills behalten" ([enabled] = `false`) bzw. "Skills neu verteilen" (`true`). */
    fun setPrestigeRespec(enabled: Boolean) = updatePrestigeDialog { _, current, dialog ->
        dialog?.withRespec(enabled, current.meta.skills.toList())
    }

    /**
     * Respec-Auswahl: [skillId] entfernen bzw. anhaengen. Angehaengt wird nur, was in der aktuellen
     * Vorschau (`GameEngine.respecPreview`) kaufbar ist.
     */
    fun toggleRespecSkill(skillId: String) = updatePrestigeDialog { engine, current, dialog ->
        if (dialog == null || !dialog.respec) return@updatePrestigeDialog dialog
        val plan = engine.respecPreview(current, dialog.selection)
        dialog.toggle(skillId, selectable = engine.skillTree.canBuy(plan.meta, skillId))
    }

    /** Respec-Auswahl leeren (alle Skills zurueck, nichts neu kaufen). */
    fun clearRespecSelection() = updatePrestigeDialog { _, _, dialog -> dialog?.cleared() }

    /**
     * Fuehrt das Prestige mit der Wahl aus dem offenen Dialog aus: `GameEngine.prestige(state,
     * NONE)` bzw. `prestige(state, respecStep(selection))`. Bei Erfolg gehen die Ereignisse
     * (`Prestiged`, ggf. Meilensteine) an [events], der Dialog schliesst, [messages] liefert
     * [GameMessage.Prestiged] und der Spielstand wird sofort gespeichert (Kapitel 7.2). Bei
     * Ablehnung bleibt alles unveraendert und es kommt [GameMessage.PrestigeFailed].
     */
    fun confirmPrestige() {
        viewModelScope.launch(gameDispatcher) {
            val currentEngine = engine ?: return@launch
            val current = state ?: return@launch
            val dialog = prestigeDialog ?: return@launch
            val step = if (dialog.respec) currentEngine.respecStep(dialog.selection) else PrestigeMetaStep.NONE
            when (val result = currentEngine.prestige(current, step)) {
                is PrestigeResult.Success -> {
                    state = result.state
                    prestigeDialog = null
                    // Die Geldrate des alten Durchlaufs gilt nicht mehr.
                    moneyRate.reset()
                    dispatchEvents(result.events)
                    messageChannel.trySend(GameMessage.Prestiged(result.fameGained))
                    publish()
                    saveCurrent()
                }
                is PrestigeResult.Failure -> {
                    messageChannel.trySend(GameMessage.PrestigeFailed(result.reason))
                    publish()
                }
            }
        }
    }

    // --- Einstellungen (AP-24) ---

    /** Spracheinstellung aendern und sofort speichern; die Activity passt die UI-Sprache an. */
    fun setLanguage(language: LanguageSetting) = updateSettings { it.copy(language = language) }

    /** Zahlenformat aendern (wirkt sofort) und speichern. */
    fun setNumberFormat(notation: NumberNotation) = updateSettings { it.copy(numberFormat = notation) }

    private fun updateSettings(change: (Settings) -> Settings) {
        viewModelScope.launch(gameDispatcher) {
            val current = state ?: return@launch
            val settings = change(current.settings)
            if (settings == current.settings) return@launch
            state = current.copy(settings = settings)
            publish()
            saveCurrent()
        }
    }

    /** Aktueller Spielstand als Base64-Text (Kapitel 7.2); das Kopieren uebernimmt die UI. */
    suspend fun exportSave(): SaveExportResult = withContext(gameDispatcher) {
        val currentTransfer = transfer ?: return@withContext SaveExportResult.Failure()
        val current = state ?: return@withContext SaveExportResult.Failure()
        currentTransfer.export(current).also {
            if (it is SaveExportResult.Failure) Log.e(TAG, "Export fehlgeschlagen: ${it.detail}")
        }
    }

    /**
     * Prueft einen Import-Text, ohne ihn zu uebernehmen (fuer die Fehleranzeige vor dem
     * Bestaetigungsdialog). Laeuft auf dem Spiel-Thread, nicht auf dem Main-Thread.
     */
    suspend fun checkImport(text: String): SaveImportResult = withContext(gameDispatcher) {
        transfer?.import(text, time.currentTimeMillis()) ?: SaveImportResult.Failure(ImportError.EMPTY)
    }

    /**
     * Uebernimmt einen Import-Text nach der Bestaetigung: erneut pruefen ([SaveTransfer.import],
     * `lastSavedAt = jetzt`, also **kein** Offline-Fortschritt), dann [replaceState]. [messages]
     * liefert [GameMessage.ImportSucceeded] bzw. [GameMessage.ImportFailed] (Stand unveraendert).
     */
    fun importSave(text: String) {
        viewModelScope.launch(gameDispatcher) {
            val currentTransfer = transfer ?: return@launch
            when (val result = currentTransfer.import(text, time.currentTimeMillis())) {
                is SaveImportResult.Success -> {
                    replaceState(result.state)
                    messageChannel.trySend(GameMessage.ImportSucceeded)
                }
                is SaveImportResult.Failure -> {
                    Log.w(TAG, "Import abgelehnt: ${result.error} ${result.detail}")
                    messageChannel.trySend(GameMessage.ImportFailed(result.error))
                }
            }
        }
    }

    /**
     * "Spielstand loeschen" (nach doppelter Bestaetigung in der UI): neues Spiel
     * ([SaveTransfer.newGame], Einstellungen bleiben), alle Spielstand-Dateien samt Backups loeschen
     * ([SaveStorage.deleteAll]) und den neuen Stand sofort speichern.
     *
     * Der neue Stand wird zuerst im Speicher gesetzt, dann wird geloescht: Ein Autosave waehrend
     * des Loeschens kann so nur den neuen Stand schreiben, nie den geloeschten.
     */
    fun deleteSave() {
        viewModelScope.launch(gameDispatcher + NonCancellable) {
            val currentTransfer = transfer ?: return@launch
            val fresh = currentTransfer.newGame(state, time.currentTimeMillis())
            replaceState(fresh, save = false)
            val deleted = try {
                withContext(ioDispatcher) { saveMutex.withLock { storage.deleteAll() } }
                true
            } catch (e: IOException) {
                Log.e(TAG, "Spielstand-Dateien nicht vollstaendig geloescht", e)
                false
            }
            saveCurrent(force = true)
            messageChannel.trySend(if (deleted) GameMessage.SaveDeleted else GameMessage.SaveDeletedIncomplete)
        }
    }

    /**
     * Ersetzt den Spielstand vollstaendig (Import, Loeschen): Modifier abgleichen, Geldrate,
     * Fund-Popups, Tabs und Dialoge zuruecksetzen, veroeffentlichen und (bei [save]) sofort speichern.
     * Muss auf [gameDispatcher] laufen.
     */
    private suspend fun replaceState(newState: GameState, save: Boolean = true) {
        val currentEngine = engine ?: return
        state = newState
        currentEngine.syncModifiers(newState)
        moneyRate.reset()
        popupBundler.reset()
        revealedTabs = TabReveal.fromIds(newState.meta.revealedTabs)
        unseen = UnseenUpdates.NONE
        prestigeDialog = null
        welcome = null
        publish()
        if (save) saveCurrent(force = true)
    }

    /** Aendert den Dialogzustand auf dem Spiel-Thread und veroeffentlicht den UI-Zustand. */
    private fun updatePrestigeDialog(change: (GameEngine, GameState, PrestigeDialogState?) -> PrestigeDialogState?) {
        viewModelScope.launch(gameDispatcher) {
            val currentEngine = engine ?: return@launch
            val current = state ?: return@launch
            prestigeDialog = change(currentEngine, current, prestigeDialog)
            publish()
        }
    }

    /**
     * Kaufaktion ueber eine Kauf-Funktion der [GameEngine] (z. B. `{ e, s -> e.buyHalls(s, mode) }`;
     * AP-25: alle Screens kaufen so). Bei Fehlschlag (z. B. Geld reicht nicht mehr) bleibt der
     * Zustand unveraendert; die UI verhindert das ohnehin ueber deaktivierte Buttons.
     */
    fun purchase(action: (GameEngine, GameState) -> PurchaseResult) = perform { engine, current ->
        when (val result = action(engine, current)) {
            is PurchaseResult.Success -> result.state
            is PurchaseResult.Failure -> current
        }
    }

    /** Laedt Daten und Spielstand. `false`, wenn die Spieldaten ungueltig sind. */
    private suspend fun load(): Boolean {
        val loadedData = try {
            withContext(ioDispatcher) { loadData() }
        } catch (e: DataValidationException) {
            Log.e(TAG, "Spieldaten ungueltig", e)
            _uiState.value = GameUiState.DataError
            return false
        }
        val modifiers = ModifierEngine.fromGameData(loadedData)
        val loadedEngine = GameEngine(loadedData, modifiers, errorHandler = numericErrorHandler)

        val result = withContext(ioDispatcher) { saveMutex.withLock { storage.load() } }
        var needsInitialSave = false
        val loadedState = when (result) {
            is SaveLoadResult.Loaded -> {
                val source = result.source
                if (source is SaveSource.Backup) {
                    Log.w(TAG, "Spielstand aus Backup ${source.index} geladen: ${result.failures}")
                    messageChannel.trySend(GameMessage.LoadedFromBackup(source.index))
                }
                result.state
            }
            SaveLoadResult.NoSave -> {
                needsInitialSave = true
                newGame(loadedData, modifiers)
            }
            is SaveLoadResult.Unreadable -> {
                Log.e(TAG, "Kein Spielstand lesbar, neues Spiel: ${result.failures}")
                messageChannel.trySend(GameMessage.SaveUnreadableNewGame)
                needsInitialSave = true
                newGame(loadedData, modifiers)
            }
        }
        loadedEngine.syncModifiers(loadedState)

        data = loadedData
        engine = loadedEngine
        purchases = Purchases(loadedData, modifiers)
        qualityTiers = QualityTiers.fromGameData(loadedData)
        texts = TextProvider.fromGameData(loadedData)
        bookMapper = BookUiMapper(loadedData)
        transfer = SaveTransfer(loadedData)
        popupBundler.reset()
        state = loadedState
        lastSavedState = if (needsInitialSave) null else loadedState
        revealedTabs = TabReveal.fromIds(loadedState.meta.revealedTabs)
        unseen = UnseenUpdates.NONE
        prestigeDialog = null
        welcome = null
        moneyRate.reset()
        publish()
        if (needsInitialSave) saveCurrent()
        return true
    }

    /** Neues Spiel; die Modifier-Engine hat noch keine Quellen (leerer Metazustand). */
    private fun newGame(gameData: GameData, modifiers: ModifierEngine): GameState =
        GameState.newGame(
            firstLayerId = gameData.layers.first().id,
            start = RunStart.values(gameData, modifiers),
            now = time.currentTimeMillis(),
        )

    /**
     * Offline-Fortschritt seit `lastSavedAt` (Kapitel 7.1, AP-23). Laeuft auf [gameDispatcher] vor
     * [runLoop], die Schleife ist also pausiert. Nicht abbrechbar: Geht die App waehrend der
     * Berechnung wieder in den Hintergrund, wird das Ergebnis trotzdem uebernommen und gespeichert
     * (`lastSavedAt = now`), sonst wuerde die Zeit beim naechsten Mal erneut gezaehlt.
     *
     * Die Rechenzeit selbst (bei 4 h effektiv etwa 1 s) wird nicht nachgeholt: Gespeichert wird
     * danach mit der aktuellen Zeit, ab der die Schleife weiterlaeuft.
     */
    private suspend fun catchUpOffline() = withContext(NonCancellable) {
        val currentEngine = engine ?: return@withContext
        val current = state ?: return@withContext
        val now = time.currentTimeMillis()
        val elapsed = WelcomeUiMapper.elapsedSeconds(current.lastSavedAt, now)
        if (WelcomeUiMapper.showsDialog(elapsed)) {
            // Lade-Indikator nur bei langer Abwesenheit; kurze Nachberechnungen sind sofort fertig.
            welcome = WelcomeUiState.Calculating(elapsed)
            publish()
        }
        val result = currentEngine.applyOffline(current, now, rng)
        state = result.state
        currentEngine.syncModifiers(result.state)
        welcome = WelcomeUiMapper.next(
            current = welcome,
            summary = result.summary,
            capSeconds = currentEngine.offline.capSeconds(),
            efficiency = currentEngine.offline.efficiency(),
        )
        publish()
        saveCurrent()
    }

    /** Spielschleife; laeuft, bis sie abgebrochen wird (Hintergrund). */
    private suspend fun runLoop() {
        var nextTick = time.monotonicNanos()
        var nextAutosave = nextTick + AUTOSAVE_INTERVAL_NANOS
        while (true) {
            tick()
            val now = time.monotonicNanos()
            if (now >= nextAutosave) {
                saveCurrent()
                nextAutosave = now + AUTOSAVE_INTERVAL_NANOS
            }
            nextTick += TICK_NANOS
            val wait = nextTick - time.monotonicNanos()
            if (wait > 0) {
                delay(wait / NANOS_PER_MILLI)
            } else {
                // Zu langsam: keine verlorenen Ticks nachholen, sondern im Takt weiterlaufen.
                // Laengere Pausen sind Offline-Zeit (AP-14).
                nextTick = time.monotonicNanos()
            }
        }
    }

    private fun tick() {
        // Im Hintergrund zaehlt die Zeit nur als Offline-Zeit (falls ein Tick die Abbruchmeldung ueberholt).
        if (!foreground.value) return
        val currentEngine = engine ?: return
        val current = state ?: return
        val result = currentEngine.advance(current, TICK_SECONDS, rng)
        if (result.state !== current) {
            moneyRate.record(result.state.run.money - current.run.money, TICK_SECONDS)
        }
        state = result.state
        dispatchEvents(result.events)
        publish()
    }

    /**
     * Verteilt Ereignisse an [events], die Fund-Popups, die Badges ([UnseenUpdates]) und die
     * Meilenstein-Toasts ([GameMessage.MilestoneReached]). Muss auf [gameDispatcher] laufen.
     */
    private fun dispatchEvents(events: List<GameEvent>) {
        val tiers = qualityTiers
        for (event in events) {
            _events.tryEmit(event)
            if (tiers != null) FindPopup.fromEvent(event, tiers)?.let(popupBundler::offer)
            if (event is GameEvent.MilestoneReached) {
                messageChannel.trySend(GameMessage.MilestoneReached(event.milestoneId, event.money, event.researchPoints))
            }
        }
        unseen = unseen.afterEvents(events, activeTab)
        popupBundler.poll(time.monotonicNanos())?.let { _findPopups.tryEmit(it) }
    }

    /**
     * Gleicht die gezeigten Tabs ab (AP-25): Neue Tabs werden in `meta.revealedTabs` geschrieben
     * (gespeichert mit dem naechsten Speichern) und ihre Hinweistexte als [GameMessage.TabHint]
     * gemeldet. Gibt den ggf. geaenderten Spielstand zurueck.
     */
    private fun updateRevealedTabs(current: GameState, currentEngine: GameEngine): GameState {
        if (revealedTabs.size == GameTab.entries.size) return current
        val update = TabReveal.update(revealedTabs, TabUnlockRules.visibleTabs(current, currentEngine::canPrestige))
        if (update.revealed === revealedTabs) return current
        revealedTabs = update.revealed
        update.hints.forEach { messageChannel.trySend(GameMessage.TabHint(it)) }
        val updated = current.copy(meta = current.meta.copy(revealedTabs = TabReveal.toIds(update.revealed)))
        state = updated
        return updated
    }

    /** Veroeffentlicht den UI-Zustand aus dem aktuellen Spielstand. */
    private fun publish() {
        val gameData = data ?: return
        val currentEngine = engine ?: return
        val current = updateRevealedTabs(state ?: return, currentEngine)
        val dig = purchases?.let { p -> DigStateMapper.map(gameData, currentEngine, p, current) } ?: DigUiState.EMPTY
        val museum = MuseumUiMapper.map(currentEngine, current, gameData)
        val lab = LabUiMapper.map(currentEngine, current, gameData)
        val fameScreen = FameUiMapper.map(currentEngine, current, prestigeDialog)
        val visibleTabs = revealedTabs.toList()
        _uiState.value = GameUiState.Ready(
            money = current.run.money,
            moneyPerSecond = moneyRate.perSecond(),
            researchPoints = current.run.researchPoints,
            fame = current.meta.fame,
            visibleTabs = visibleTabs,
            settings = current.settings,
            qualityTiers = gameData.balance.qualityTiers,
            texts = texts,
            dig = dig,
            museum = museum,
            lab = lab,
            // Gecacht: neu gebaut nur bei geaendertem Buch, erforschten Arten oder Schichten.
            book = bookMapper?.map(currentEngine, current) ?: BookUiState.EMPTY,
            fameScreen = fameScreen,
            welcome = welcome,
            badges = TabBadges.badges(
                visibleTabs = visibleTabs,
                activeTab = activeTab,
                unseen = unseen,
                affordable = TabBadges.affordableTabs(dig, museum, lab),
                canPrestige = fameScreen.canPrestige,
            ),
        )
    }

    /**
     * Speichert den aktuellen Zustand mit `lastSavedAt = jetzt`, sofern er sich seit dem letzten
     * Speichern geaendert hat oder [force] gesetzt ist. Muss auf [gameDispatcher] laufen.
     */
    private suspend fun saveCurrent(force: Boolean = false) {
        val current = state ?: return
        if (!force && current === lastSavedState) return
        val stamped = current.copy(lastSavedAt = time.currentTimeMillis())
        state = stamped
        lastSavedState = stamped
        try {
            withContext(ioDispatcher + NonCancellable) { saveMutex.withLock { storage.save(stamped) } }
        } catch (e: IOException) {
            onSaveFailed(e)
        } catch (e: SaveException) {
            onSaveFailed(e)
        }
    }

    private fun onSaveFailed(error: Exception) {
        Log.e(TAG, "Speichern fehlgeschlagen", error)
        lastSavedState = null
        messageChannel.trySend(GameMessage.SaveFailed)
    }

    companion object {
        private const val TAG = "GameViewModel"

        /** Tick-Laenge in Sekunden (10 Ticks/s, Kapitel 10.3). */
        const val TICK_SECONDS: Double = 0.1

        private const val NANOS_PER_MILLI: Long = 1_000_000L
        private const val TICK_NANOS: Long = 100 * NANOS_PER_MILLI

        /** Autosave-Intervall: 30 s (Kapitel 7.2). */
        const val AUTOSAVE_INTERVAL_NANOS: Long = 30_000 * NANOS_PER_MILLI

        private const val EVENT_BUFFER = 64
        private const val POPUP_BUFFER = 4

        /** Factory fuer `viewModel(factory = GameViewModel.Factory)`. */
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) { "Application fehlt in den CreationExtras" }
                val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                GameViewModel(
                    storage = SaveStorage(app.filesDir),
                    time = TimeSource.System,
                    numericErrorHandler = if (debuggable) {
                        NumericErrorHandler.THROW
                    } else {
                        NumericErrorHandler { Log.e(TAG, "Tick verworfen", it) }
                    },
                )
            }
        }
    }
}
