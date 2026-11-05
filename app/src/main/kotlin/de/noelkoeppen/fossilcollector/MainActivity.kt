package de.noelkoeppen.fossilcollector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ProcessLifecycleOwner
import de.noelkoeppen.fossilcollector.platform.AppLifecycleObserver
import de.noelkoeppen.fossilcollector.ui.shell.AppShell
import de.noelkoeppen.fossilcollector.vm.GameViewModel

class MainActivity : ComponentActivity() {

    private val gameViewModel: GameViewModel by viewModels { GameViewModel.Factory }

    /** Prozess-Lifecycle: Speichern und Anhalten, wenn die App in den Hintergrund geht (Kapitel 7.2). */
    private val processObserver by lazy {
        AppLifecycleObserver(
            onForeground = gameViewModel::onAppForeground,
            onBackground = gameViewModel::onAppBackground,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        setContent {
            AppShell(viewModel = gameViewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        gameViewModel.onAppForeground()
    }

    /**
     * Speichern in `onStop` (Kapitel 7.2), zusaetzlich zum Prozess-Lifecycle, der den Hintergrund
     * erst verzoegert meldet. Bei Konfigurationswechseln (Drehen) wird nicht gespeichert.
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
