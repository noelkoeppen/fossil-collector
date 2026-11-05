package de.noelkoeppen.fossilcollector.core

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Rauchtest fuer das Projektgeruest (AP-01): JUnit 5 laeuft und der Ressourcenordner
 * fuer die Spieldaten (Kapitel 11) liegt auf dem Klassenpfad.
 */
class ProjectSetupTest {

    @Test
    fun dataResourceFolderIsOnClasspath() {
        val marker = javaClass.classLoader.getResource("data/balance.json")
        assertNotNull(marker, "core/src/main/resources/data fehlt auf dem Klassenpfad")
    }
}
