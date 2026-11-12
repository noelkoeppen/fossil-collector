package de.noelkoeppen.fossilcollector

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.platform.AppLanguageStore
import de.noelkoeppen.fossilcollector.platform.AppLifecycleObserver
import de.noelkoeppen.fossilcollector.platform.AppLocale
import de.noelkoeppen.fossilcollector.ui.shell.AppShell
import de.noelkoeppen.fossilcollector.vm.GameUiState
import de.noelkoeppen.fossilcollector.vm.GameViewModel

class MainActivity : ComponentActivity() {

    private val gameViewModel: GameViewModel by viewModels { GameViewModel.Factory }

    /** Prozess-Lifecycle: Speichern, wenn die App in den Hintergrund geht. */
    private val processObserver by lazy {
        AppLifecycleObserver(onBackground = gameViewModel::onAppBackground)
    }

    /** Spracheinstellung, mit der diese Activity erstellt wurde (siehe [AppLocale]). */
    private var appliedLanguage: LanguageSetting = LanguageSetting.SYSTEM

    /** Sprache des Systems (vor dem Ueberschreiben), fuer "System". */
    private var systemLanguage: String = ""

    /**
     * UI-Sprache (AP-24): Locale der Ressourcen gemaess gespiegelter Einstellung
     * ueberschreiben, bevor irgendetwas Ressourcen liest.
     */
    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = AppLanguageStore(newBase).read()
        systemLanguage = AppLocale.systemLanguage(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase, appliedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        setContent {
            val uiState by gameViewModel.uiState.collectAsStateWithLifecycle()
            val language = (uiState as? GameUiState.Ready)?.settings?.language
            LaunchedEffect(language) {
                if (language != null) applyLanguage(language)
            }
            AppShell(viewModel = gameViewModel)
        }
    }

    /**
     * Gleicht die Sprache des Spielstands mit der Activity ab: Spiegel aktualisieren und, wenn sich
     * die UI-Sprache aendert, die Activity neu erstellen (Spielstand und ViewModel bleiben erhalten).
     */
    private fun applyLanguage(language: LanguageSetting) {
        if (language == appliedLanguage) return
        AppLanguageStore(this).write(language)
        if (AppLocale.needsRecreate(appliedLanguage, language, systemLanguage)) {
            recreate()
        } else {
            appliedLanguage = language
        }
    }

    /**
     * Speichern in `onStop`, zusaetzlich zum Prozess-Lifecycle, der den Hintergrund
     * erst verzoegert meldet. Bei Konfigurationswechseln (Drehen, Sprachwechsel per `recreate`)
     * wird nicht gespeichert.
     */
    override fun onStop() {
        if (!isChangingConfigurations) gameViewModel.onAppBackground()
        super.onStop()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
    }
}
