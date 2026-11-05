package de.noelkoeppen.fossilcollector.ui.book

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.BodyPlan
import de.noelkoeppen.fossilcollector.core.engine.CompletionLevel
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId

/**
 * Sammelbuch-Ausschnitt des UI-Zustands (AP-20, Kapitel 4.7 und 8.3). Wird von [BookUiMapper]
 * ausserhalb der Composables gebaut und enthaelt Rohwerte und IDs; Namen loest die UI ueber den
 * gemeinsamen `TextProvider` auf (`rememberTextProvider`).
 *
 * @property layers alle Schichten in Datenreihenfolge (auch gesperrte), je Schicht ihre Arten
 */
@Immutable
data class BookUiState(
    val layers: List<BookLayerUi>,
) {
    private val speciesById: Map<String, BookSpeciesUi> by lazy {
        layers.flatMap { it.species }.associateBy { it.speciesId }
    }

    /** Art [speciesId] oder `null`, wenn sie nicht im Buch steht. */
    fun species(speciesId: String): BookSpeciesUi? = speciesById[speciesId]

    companion object {
        /** Leerer Zustand (Ladezustand, Vorschauen anderer Screens). */
        val EMPTY = BookUiState(emptyList())
    }
}

/**
 * Ein Reiter des Sammelbuchs.
 *
 * @property layerId Schicht-ID (Name ueber `TextProvider.layerName`)
 * @property unlocked Schicht im aktuellen Run freigeschaltet; gesperrte Schichten zeigen ihre Arten trotzdem
 * @property species Arten der Schicht in Datenreihenfolge
 */
@Immutable
data class BookLayerUi(
    val layerId: String,
    val unlocked: Boolean,
    val species: List<BookSpeciesUi>,
) {
    /** Arten mit mindestens einem Bucheintrag (oder erforscht). */
    val discoveredCount: Int get() = species.count { it.discovered }

    /** Arten mit Completion-Stufe 2. */
    val completeCount: Int get() = species.count { it.level == CompletionLevel.SKELETON_COMPLETE }
}

/**
 * Eine Art im Sammelbuch (Raster und Art-Detail).
 *
 * @property bodyPlan Koerperbau (Platzhalter-Grafik, Kapitel 8.5)
 * @property discovered mindestens ein Knochen im Buch oder erforscht; sonst zeigt das Raster "?"
 * @property level Completion-Stufe (`SpeciesCompletion.level`)
 * @property bonesInBook Knochen mit Bucheintrag
 * @property bonesTotal Knochen der Art
 * @property minTierIndex niedrigste Stufe der Knochen (nur bei Stufe 2), Index in `balance.qualityTiers`
 * @property minTierId ID der Stufe zu [minTierIndex]
 * @property bones Knochen in der Reihenfolge aus `species.json`
 * @property skeleton Eingabe fuer [SkeletonView] (boneId -> Stufe und Zonen), gleiche Reihenfolge wie [bones]
 * @property bookBonus Buch-Bonus der Art (wirksam ab Stufe 2)
 * @property researchedBonus Pauschal-Bonus fuer erforschte Arten (wirksam ab Stufe 1)
 */
@Immutable
data class BookSpeciesUi(
    val speciesId: String,
    val layerId: String,
    val bodyPlan: BodyPlan,
    val discovered: Boolean,
    val level: CompletionLevel,
    val bonesInBook: Int,
    val bonesTotal: Int,
    val minTierIndex: Int?,
    val minTierId: String?,
    val bones: List<BookBoneUi>,
    val skeleton: Map<String, SkeletonBone>,
    val bookBonus: BookBonusUi,
    val researchedBonus: ResearchedBonusUi,
)

/**
 * Ein Knochen im Art-Detail. Ohne Bucheintrag sind [quality], [tierId] und [tierIndex] `null`.
 * Bei mehreren Eintraegen (Alter/Geschlecht, [Spaeter]) zaehlt der beste.
 *
 * @property reconstructed Bucheintrag stammt aus einer Rekonstruktion
 * @property zones Zonen in der Reihenfolge aus `bones.json`
 */
@Immutable
data class BookBoneUi(
    val boneId: String,
    val quality: Double?,
    val tierId: String?,
    val tierIndex: Int?,
    val reconstructed: Boolean,
    val zones: List<BookZoneUi>,
) {
    /** `true`, wenn der Knochen im Buch steht. */
    val inBook: Boolean get() = quality != null
}

/**
 * Eine Zone eines Knochens.
 *
 * @property zoneId Zonen-ID (Name ueber `TextProvider.zoneName(boneId, zoneId)`)
 * @property quality Qualitaet 0..1 des Buch-Fundes; `null` ohne Eintrag oder wenn der Eintrag keine
 *   Zonen hat (aeltere Spielstaende, `BookEntry.zones` leer)
 */
@Immutable
data class BookZoneUi(
    val zoneId: String,
    val quality: Double?,
)

/**
 * Buch-Bonus einer Art (Kapitel 4.7): `baseValue * bookTierFactor[minTier]`.
 *
 * @property active Stufe 2 erreicht, der Bonus wirkt
 * @property value wirksamer Wert (nur wenn [active])
 * @property factor Stufenfaktor `bookTierFactor[minTier]` (nur wenn [active])
 * @property minValue Wert bei der niedrigsten Stufe (Vorschau "ab Stufe 2")
 * @property maxValue Wert bei der hoechsten Stufe (Vorschau "ab Stufe 2")
 */
@Immutable
data class BookBonusUi(
    val stat: StatId,
    val op: ModifierOp,
    val baseValue: Double,
    val active: Boolean,
    val value: Double?,
    val factor: Double?,
    val minValue: Double,
    val maxValue: Double,
)

/**
 * Pauschal-Bonus fuer erforschte Arten (`balance.researchedBonus`).
 *
 * @property active Art erforscht (Stufe 1), der Bonus wirkt
 */
@Immutable
data class ResearchedBonusUi(
    val stat: StatId,
    val op: ModifierOp,
    val value: Double,
    val active: Boolean,
)
