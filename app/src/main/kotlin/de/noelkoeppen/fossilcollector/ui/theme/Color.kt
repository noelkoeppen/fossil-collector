package de.noelkoeppen.fossilcollector.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Palette "Museum und Gestein" (Kapitel 8.1 und 8.5): dunkler Gesteinsgrund, knochenfarbene
 * Schrift und Formen (#E8DFC8), Sandstein bzw. Bernstein als Akzent. Reine Gestaltung, keine
 * Spielwerte. Die Qualitaetsfarben stehen in `balance.json` (siehe QualityColors.kt).
 */

/** Knochenfarbe fuer Formen und Text auf dunklem Grund (Kapitel 8.5). */
val Bone = Color(0xFFE8DFC8)
val BoneDim = Color(0xFFB9B09A)

/** Gesteinsgrund (entspricht `@color/background` des Plattform-Themes). */
val Rock900 = Color(0xFF1C1A17)
val Rock800 = Color(0xFF25221E)
val Rock700 = Color(0xFF302C27)
val Rock600 = Color(0xFF3D3832)
val Rock500 = Color(0xFF5A534A)

/** Sandstein/Bernstein als Hauptakzent. */
val Sandstone = Color(0xFFD9A55B)
val SandstoneDark = Color(0xFF6B4A1E)
val OnSandstone = Color(0xFF2A1C08)

/** Schiefer-Blaugrau als zweiter Akzent. */
val Slate = Color(0xFF9DB0B8)
val SlateDark = Color(0xFF34444B)

/** Ocker fuer den dritten Akzent (z. B. Ruhm). */
val Ochre = Color(0xFFC98E4E)
val OchreDark = Color(0xFF5A3816)

val ErrorRed = Color(0xFFE5867A)
val OnErrorRed = Color(0xFF3B0A05)
val ErrorContainerRed = Color(0xFF6E241B)

/** Helles Thema (nur falls spaeter eine Einstellung kommt); Standard ist dunkel. */
val Parchment = Color(0xFFF4EEDF)
val InkBrown = Color(0xFF2B241C)
