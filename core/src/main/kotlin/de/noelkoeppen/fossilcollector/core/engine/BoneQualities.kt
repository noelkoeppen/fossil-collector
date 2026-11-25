package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.model.Piece

/**
 * Qualitaet je Knochen einer Art fuer die 3D-Ansicht (AP2-10). Die Ansicht faerbt jeden Knochen
 * nach der Stufe seiner Qualitaet ([QualityTiers]); welche Qualitaet ein Knochen hat, kommt von hier.
 *
 * Zuordnen (AP2-11) und Sammelbuch (AP2-12) bringen eigene Zustaende (zugeordnete Knochen, beste
 * Funde aller Zeiten); bis dahin zeigt die Testansicht das Inventar oder Beispielqualitaeten.
 */
object BoneQualities {

    /** Beste Qualitaet je Knochen der Art [speciesId] unter [pieces]; Knochen ohne Stueck fehlen. */
    fun bestByBone(pieces: List<Piece>, speciesId: String): Map<String, Double> {
        val best = LinkedHashMap<String, Double>()
        pieces.filter { it.speciesId == speciesId }.forEach { piece ->
            val current = best[piece.boneId]
            if (current == null || piece.quality > current) best[piece.boneId] = piece.quality
        }
        return best
    }

    /**
     * Beispielqualitaeten zum Vorfuehren der Einfaerbung: Die Knochen gehen der Reihe nach durch
     * alle Stufen (Knochen `i` bekommt Stufe `i mod Stufenzahl`), jeweils mit der Untergrenze der
     * Stufe als Qualitaet. Die Werte kommen damit nur aus `balance.json: qualityTiers`.
     */
    fun tierSamples(boneIds: List<String>, tiers: QualityTiers): Map<String, Double> =
        boneIds.withIndex().associate { (i, id) -> id to tiers.tiers[i % tiers.tiers.size].minQuality }
}
