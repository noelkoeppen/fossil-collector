package de.noelkoeppen.fossilcollector.ui.welcome

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.noelkoeppen.fossilcollector.R
import java.util.Locale
import kotlin.math.floor

/**
 * Lokalisierte Einheiten fuer [formatDuration]; jedes Muster enthaelt genau einen Platzhalter
 * `%1$d`, z. B. `%1$d h`. Die Muster kommen aus den Ressourcen ([rememberDurationUnits]).
 */
data class DurationUnits(
    val days: String,
    val hours: String,
    val minutes: String,
    val seconds: String,
    val separator: String = " ",
)

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L

/**
 * Dauer in ganzen Sekunden (abgerundet) mit den zwei groessten Einheiten, z. B. `2 h 15 min`,
 * `1 d 3 h`, `5 min 30 s` oder `45 s`. Ist die zweite Einheit 0, entfaellt sie (`4 h`).
 * Negative und nicht endliche Werte ergeben `0 s`.
 */
fun formatDuration(seconds: Double, units: DurationUnits): String {
    val total = if (seconds.isFinite() && seconds > 0.0) floor(seconds).toLong() else 0L
    val parts = listOf(
        units.days to total / SECONDS_PER_DAY,
        units.hours to total % SECONDS_PER_DAY / SECONDS_PER_HOUR,
        units.minutes to total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
        units.seconds to total % SECONDS_PER_MINUTE,
    )
    val first = parts.indexOfFirst { it.second > 0 }
    if (first < 0) return unit(units.seconds, 0)
    val shown = mutableListOf(unit(parts[first].first, parts[first].second))
    parts.getOrNull(first + 1)?.takeIf { it.second > 0 }?.let { shown += unit(it.first, it.second) }
    return shown.joinToString(units.separator)
}

private fun unit(pattern: String, value: Long): String = String.format(Locale.ROOT, pattern, value)

/** Einheiten aus `strings_welcome.xml` in der aktuellen App-Sprache. */
@Composable
fun rememberDurationUnits(): DurationUnits = DurationUnits(
    days = stringResource(R.string.welcome_unit_days),
    hours = stringResource(R.string.welcome_unit_hours),
    minutes = stringResource(R.string.welcome_unit_minutes),
    seconds = stringResource(R.string.welcome_unit_seconds),
)
