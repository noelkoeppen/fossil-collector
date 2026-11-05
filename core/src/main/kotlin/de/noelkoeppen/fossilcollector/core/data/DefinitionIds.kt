package de.noelkoeppen.fossilcollector.core.data

/**
 * IDs von Definitionen, die fest einem Feld des Spielstands zugeordnet sind (z. B. `RunState.halls`).
 * Das sind keine Spielwerte, sondern Verknuepfungen zwischen Daten und Zustand. Die Werte selbst
 * (Kosten, Wachstum, Gratis-Anzahl, Effekte) stehen ausschliesslich in den JSON-Dateien.
 */
object DefinitionIds {
    /** Generator Praeparator (`generators.json`), Bestand `RunState.preparators`. */
    const val PREPARATOR: String = "preparator"

    /** Generator Museumshalle (`generators.json`), Bestand `RunState.halls`. */
    const val HALL: String = "hall"

    /** Generator Laborplatz (`generators.json`), Bestand `RunState.labSlots`. */
    const val LAB_SLOT: String = "lab_slot"

    /** Upgrade Werkzeugstufen (`upgrades.json`), Stufe `RunState.toolLevel`. */
    const val TOOLS: String = "tools"
}
