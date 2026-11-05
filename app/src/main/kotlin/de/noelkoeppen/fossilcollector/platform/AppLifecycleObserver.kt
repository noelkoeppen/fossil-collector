package de.noelkoeppen.fossilcollector.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Meldet Vorder- und Hintergrund des gesamten Prozesses (`ProcessLifecycleOwner`, Kapitel 7.2).
 * `ON_STOP` kommt mit kurzer Verzoegerung, nachdem die letzte Activity gestoppt wurde, und nicht
 * bei Konfigurationswechseln.
 */
class AppLifecycleObserver(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = onForeground()

    override fun onStop(owner: LifecycleOwner) = onBackground()
}
