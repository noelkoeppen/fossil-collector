package de.noelkoeppen.fossilcollector.vm

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
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.save.SaveException
import de.noelkoeppen.fossilcollector.platform.SaveLoadResult
import de.noelkoeppen.fossilcollector.platform.SaveSource
import de.noelkoeppen.fossilcollector.platform.SaveStorage
import de.noelkoeppen.fossilcollector.platform.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Bindeglied zwischen `core` und UI.
 *
 * Uebergangsstand nach AP2-02: Es gibt keine Spielschleife und keine Zeit-Simulation mehr
 * (GAME_DESIGN.md, Kapitel 0 Regel 9). Der Spielstand aendert sich nur durch Aktionen; im Moment
 * sind das die Einstellungen sowie Export, Import und Loeschen des Spielstands.
 *
 * - **Laden:** Spieldaten ueber [loadData] (Standard: [DataRepository] aus den Klassenpfad-Ressourcen
 *   von `core`, die im APK liegen; ungueltige Daten fuehren zu [GameUiState.DataError]), dann den
 *   Spielstand ueber [SaveStorage]. Ohne lesbaren Spielstand: neues Spiel ueber `GameState.newGame`.
 * - **Speichern:** direkt nach jeder Aenderung, nach dem Anlegen eines neuen Spiels und beim Wechsel
 *   in den Hintergrund ([onAppBackground]). `lastSavedAt` wird beim Speichern auf die aktuelle Zeit
 *   gesetzt.
 * - **Einstellungen:** Die Sprache aendert den Spielstand und wird sofort gespeichert. Export, Import
 *   und "Spielstand loeschen" laufen ueber [SaveTransfer]; ein importierter bzw. neuer Stand ersetzt
 *   den aktuellen ueber [replaceState].
 *
 * Threading: Der Spielstand wird ausschliesslich auf [gameDispatcher] (Parallelitaet 1) angefasst.
 * Dateizugriffe laufen auf [ioDispatcher] unter einem Mutex.
 *
 * @param storage Spielstand-Dateien
 * @param time Zeitquelle
 * @param loadData laedt und validiert die Spieldaten
 */
class GameViewModel(
    private val storage: SaveStorage,
    private val time: TimeSource = TimeSource.System,
    private val loadData: () -> GameData = { DataRepository().load() },
    private val gameDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GameUiState>(GameUiState.Loading)

    /** Aktueller UI-Zustand (unveraenderlich). */
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<GameMessage>(Channel.BUFFERED)

    /** Einmalige Hinweise (Backup geladen, Speichern fehlgeschlagen ...); werden genau einmal zugestellt. */
    val messages: Flow<GameMessage> = messageChannel.receiveAsFlow()

    // --- Nur auf gameDispatcher ---
    private var data: GameData? = null
    private var state: GameState? = null
    private var lastSavedState: GameState? = null
    private var texts: TextProvider = TextProvider.EMPTY
    private var transfer: SaveTransfer? = null

    private val saveMutex = Mutex()

    init {
        viewModelScope.launch(gameDispatcher) { load() }
    }

    /** App geht in den Hintergrund: speichern (idempotent). */
    fun onAppBackground() {
        // NonCancellable: Das Speichern soll auch dann fertig werden, wenn das ViewModel gerade
        // geraeumt wird (Activity beendet).
        viewModelScope.launch(gameDispatcher + NonCancellable) { saveCurrent() }
    }

    // --- Einstellungen ---

    /** Spracheinstellung aendern und sofort speichern; die Activity passt die UI-Sprache an. */
    fun setLanguage(language: LanguageSetting) = updateSettings { it.copy(language = language) }

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

    /** Aktueller Spielstand als Base64-Text; das Kopieren uebernimmt die UI. */
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
     * Uebernimmt einen Import-Text nach der Bestaetigung: erneut pruefen ([SaveTransfer.import]),
     * dann [replaceState]. [messages] liefert [GameMessage.ImportSucceeded] bzw.
     * [GameMessage.ImportFailed] (Stand unveraendert).
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
     * Der neue Stand wird zuerst im Speicher gesetzt, dann wird geloescht: Ein Speichern waehrend
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
     * Ersetzt den Spielstand vollstaendig (Import, Loeschen), veroeffentlicht ihn und speichert
     * (bei [save]) sofort. Muss auf [gameDispatcher] laufen.
     */
    private suspend fun replaceState(newState: GameState, save: Boolean = true) {
        state = newState
        publish()
        if (save) saveCurrent(force = true)
    }

    /** Laedt Daten und Spielstand. Bei ungueltigen Spieldaten bleibt die App bei [GameUiState.DataError]. */
    private suspend fun load() {
        val loadedData = try {
            withContext(ioDispatcher) { loadData() }
        } catch (e: DataValidationException) {
            Log.e(TAG, "Spieldaten ungueltig", e)
            _uiState.value = GameUiState.DataError
            return
        }

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
                GameState.newGame(now = time.currentTimeMillis())
            }
            is SaveLoadResult.Unreadable -> {
                Log.e(TAG, "Kein Spielstand lesbar, neues Spiel: ${result.failures}")
                messageChannel.trySend(GameMessage.SaveUnreadableNewGame)
                needsInitialSave = true
                GameState.newGame(now = time.currentTimeMillis())
            }
        }

        data = loadedData
        texts = TextProvider.fromGameData(loadedData)
        transfer = SaveTransfer()
        state = loadedState
        lastSavedState = if (needsInitialSave) null else loadedState
        publish()
        if (needsInitialSave) saveCurrent()
    }

    /** Veroeffentlicht den UI-Zustand aus dem aktuellen Spielstand. */
    private fun publish() {
        val gameData = data ?: return
        val current = state ?: return
        _uiState.value = GameUiState.Ready(
            settings = current.settings,
            qualityTiers = gameData.balance.qualityTiers,
            texts = texts,
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

        /** Factory fuer `viewModel(factory = GameViewModel.Factory)`. */
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) { "Application fehlt in den CreationExtras" }
                GameViewModel(
                    storage = SaveStorage(app.filesDir),
                    time = TimeSource.System,
                )
            }
        }
    }
}
