package de.noelkoeppen.fossilcollector.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Dunkles Standard-Farbschema in Museums- bzw. Gesteinsoptik (Kapitel 8.1). */
val FossilDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Sandstone,
    onPrimary = OnSandstone,
    primaryContainer = SandstoneDark,
    onPrimaryContainer = Bone,
    secondary = Slate,
    onSecondary = Rock900,
    secondaryContainer = SlateDark,
    onSecondaryContainer = Bone,
    tertiary = Ochre,
    onTertiary = Rock900,
    tertiaryContainer = OchreDark,
    onTertiaryContainer = Bone,
    background = Rock900,
    onBackground = Bone,
    surface = Rock900,
    onSurface = Bone,
    surfaceVariant = Rock700,
    onSurfaceVariant = BoneDim,
    surfaceContainerLowest = Rock900,
    surfaceContainerLow = Rock800,
    surfaceContainer = Rock800,
    surfaceContainerHigh = Rock700,
    surfaceContainerHighest = Rock600,
    outline = Rock500,
    outlineVariant = Rock600,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorContainerRed,
    onErrorContainer = Bone,
)

/** Helles Schema als Reserve; die App startet immer dunkel. */
val FossilLightColorScheme: ColorScheme = lightColorScheme(
    primary = SandstoneDark,
    onPrimary = Parchment,
    secondary = SlateDark,
    onSecondary = Parchment,
    tertiary = OchreDark,
    onTertiary = Parchment,
    background = Parchment,
    onBackground = InkBrown,
    surface = Parchment,
    onSurface = InkBrown,
)

/**
 * Theme der App. Dunkel ist Standard (Kapitel 8.1); [darkTheme] ist nur fuer Vorschauen bzw. eine
 * spaetere Einstellung vorgesehen. Stellt zusaetzlich [LocalQualityColors] bereit.
 *
 * @param qualityColors Qualitaetsfarben aus den Spieldaten, siehe [QualityColors.fromTiers]
 */
@Composable
fun FossilCollectorTheme(
    darkTheme: Boolean = true,
    qualityColors: QualityColors = QualityColors.EMPTY,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalQualityColors provides qualityColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) FossilDarkColorScheme else FossilLightColorScheme,
            content = content,
        )
    }
}
