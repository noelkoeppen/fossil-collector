package de.noelkoeppen.fossilcollector.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Meldet, wenn der gesamte Prozess in den Hintergrund geht (`ProcessLifecycleOwner`), damit der
 * Spielstand gespeichert wird. `ON_STOP` kommt mit kurzer Verzoegerung, nachdem die letzte Activity
 * gestoppt wurde, und nicht bei Konfigurationswechseln.
 */
class AppLifecycleObserver(
    private val onBackground: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) = onBackground()
}
