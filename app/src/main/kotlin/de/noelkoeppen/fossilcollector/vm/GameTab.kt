package de.noelkoeppen.fossilcollector.vm

/**
 * Tabs der unteren Navigation in Anzeigereihenfolge (Kapitel 8.1):
 * Grabung, Labor, Museum, Sammelbuch, Ruhm.
 *
 * @property id stabile ID im Spielstand (`meta.revealedTabs`, AP-25); nie aendern
 */
enum class GameTab(val id: String) {
    DIG("dig"),
    LAB("lab"),
    MUSEUM("museum"),
    BOOK("book"),
    FAME("fame"),
    ;

    companion object {
        /** Tab zur gespeicherten [id] oder `null`, wenn unbekannt. */
        fun fromId(id: String): GameTab? = entries.firstOrNull { it.id == id }
    }
}
