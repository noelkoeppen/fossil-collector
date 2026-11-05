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
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.GameEvent
import de.noelkoeppen.fossilcollector.core.engine.NumericErrorHandler
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.core.save.SaveException
import de.noelkoeppen.fossilcollector.platform.SaveLoadResult
import de.noelkoeppen.fossilcollector.platform.SaveSource
import de.noelkoeppen.fossilcollector.platform.SaveStorage
import de.noelkoeppen.fossilcollector.platform.TimeSource
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
 *   Offline-Fortschritt beim Start folgt in AP-14/AP-23; hier wird nur `lastSavedAt` gepflegt.
 * - **Schleife:** 10 Ticks/s, je Tick `GameEngine.advance(state, 0.1, rng)`. Danach wird ein
 *   unveraenderlicher [GameUiState] per [uiState] veroeffentlicht, Ereignisse gehen an [events].
 *   Die Schleife laeuft nur im Vordergrund (siehe [onAppForeground]/[onAppBackground]).
 * - **Speichern:** Autosave alle [AUTOSAVE_INTERVAL_NANOS], beim Wechsel in den Hintergrund
 *   (Prozess-Lifecycle `ON_STOP` ueber `platform/AppLifecycleObserver` und `Activity.onStop`) sowie
 *   direkt nach dem Anlegen eines neuen Spiels. `lastSavedAt` wird beim Speichern auf die aktuelle Zeit gesetzt.
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
) : ViewModel() {

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

    // --- Nur auf gameDispatcher ---
    private var data: GameData? = null
    private var engine: GameEngine? = null
    private var state: GameState? = null
    private var lastSavedState: GameState? = null
    private var revealedTabs: Set<GameTab> = emptySet()
    private val moneyRate = RateTracker()

    private val foreground = MutableStateFlow(false)
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch(gameDispatcher) {
            if (!load()) return@launch
            foreground.collectLatest { active -> if (active) runLoop() }
        }
    }

    /** App ist sichtbar: Spielschleife laeuft (idempotent). */
    fun onAppForeground() {
        foreground.value = true
    }

    /** App geht in den Hintergrund: Schleife anhalten und speichern (idempotent). */
    fun onAppBackground() {
        foreground.value = false
        // NonCancellable: Das Speichern soll auch dann fertig werden, wenn das ViewModel gerade
        // geraeumt wird (Activity beendet).
        viewModelScope.launch(gameDispatcher + NonCancellable) { saveCurrent() }
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
            state = action(currentEngine, current)
            publish()
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
        state = loadedState
        lastSavedState = if (needsInitialSave) null else loadedState
        revealedTabs = emptySet()
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
        val currentEngine = engine ?: return
        val current = state ?: return
        val result = currentEngine.advance(current, TICK_SECONDS, rng)
        if (result.state !== current) {
            moneyRate.record(result.state.run.money - current.run.money, TICK_SECONDS)
        }
        state = result.state
        for (event in result.events) _events.tryEmit(event)
        publish()
    }

    /** Veroeffentlicht den UI-Zustand aus dem aktuellen Spielstand. */
    private fun publish() {
        val current = state ?: return
        val gameData = data ?: return
        revealedTabs = TabUnlockRules.revealTabs(revealedTabs, current)
        _uiState.value = GameUiState.Ready(
            money = current.run.money,
            moneyPerSecond = moneyRate.perSecond(),
            researchPoints = current.run.researchPoints,
            fame = current.meta.fame,
            visibleTabs = revealedTabs.toList(),
            settings = current.settings,
            qualityTiers = gameData.balance.qualityTiers,
        )
    }

    /**
     * Speichert den aktuellen Zustand mit `lastSavedAt = jetzt`, sofern er sich seit dem letzten
     * Speichern geaendert hat. Muss auf [gameDispatcher] laufen.
     */
    private suspend fun saveCurrent() {
        val current = state ?: return
        if (current === lastSavedState) return
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
