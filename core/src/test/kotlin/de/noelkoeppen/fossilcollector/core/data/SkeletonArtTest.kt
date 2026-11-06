package de.noelkoeppen.fossilcollector.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Laden, Fallback-Regel und Pruefung der Skelett-Grafiken (Kapitel 8.5, AP-21). */
class SkeletonArtTest {

    private val bones = listOf(
        BoneDefinition("skull", listOf("snout", "orbit")),
        BoneDefinition("jaw", listOf("tip")),
    )

    private fun artJson(name: String, skullZones: String = """{"id":"snout","d":"M0,0 Z"},{"id":"orbit","d":"M1,1 Z"}""") =
        """
        {"name":"$name","viewBox":{"x":0,"y":0,"width":100,"height":50},
         "bones":[{"id":"skull","zones":[$skullZones]},{"id":"jaw","zones":[{"id":"tip","d":"m0,2 z"}]}]}
        """.trimIndent()

    private fun repository(files: Map<String, String>) =
        SkeletonArtRepository { path -> files[path] }

    @Test
    fun parsesViewBoxBonesAndZones() {
        val art = checkNotNull(repository(mapOf("skeletons/skeleton_biped.json" to artJson("biped"))).load("skeleton_biped.json"))
        assertEquals("biped", art.name)
        assertEquals(SkeletonViewBox(0.0, 0.0, 100.0, 50.0), art.viewBox)
        assertEquals(listOf("skull", "jaw"), art.bones.map { it.id })
        assertEquals(listOf("snout", "orbit"), art.bonesById.getValue("skull").zones.map { it.id })
        assertEquals(emptyList<String>(), SkeletonArtValidator.validate("f", art, bones))
    }

    @Test
    fun missingFileIsNullAndBrokenFileThrows() {
        val repo = repository(mapOf("skeletons/skeleton_broken.json" to """{"name":"x"}"""))
        assertNull(repo.load("skeleton_missing.json"))
        val e = assertThrows<DataValidationException> { repo.load("skeleton_broken.json") }
        assertTrue(e.errors.single().startsWith("skeletons/skeleton_broken.json"))
    }

    @Test
    fun speciesArtWinsOverBodyPlanPlaceholder() {
        val repo = repository(
            mapOf(
                "skeletons/skeleton_quadruped.json" to artJson("quadruped"),
                "skeletons/skeleton_cave_bear.json" to artJson("cave_bear"),
            ),
        )
        assertEquals("skeleton_cave_bear.json", repo.resolveFile("cave_bear", BodyPlan.QUADRUPED))
        assertEquals("cave_bear", repo.forSpecies("cave_bear", BodyPlan.QUADRUPED)?.name)
    }

    @Test
    fun fallsBackToPlaceholderOfBodyPlan() {
        val repo = repository(
            mapOf(
                "skeletons/skeleton_quadruped.json" to artJson("quadruped"),
                "skeletons/skeleton_biped.json" to artJson("biped"),
            ),
        )
        assertEquals("skeleton_biped.json", repo.resolveFile("titanis", BodyPlan.BIPED))
        assertEquals("quadruped", repo.forSpecies("smilodon", BodyPlan.QUADRUPED)?.name)
        assertNull(repository(emptyMap()).forSpecies("smilodon", BodyPlan.QUADRUPED))
        assertEquals(
            listOf("skeleton_smilodon.json", "skeleton_quadruped.json"),
            SkeletonArtRepository.candidates("smilodon", BodyPlan.QUADRUPED),
        )
    }

    @Test
    fun validatorDetectsMissingUnknownAndReorderedZones() {
        val repo = repository(
            mapOf(
                "skeletons/skeleton_a.json" to artJson("a", """{"id":"orbit","d":"M1,1 Z"},{"id":"snout","d":"M0,0 Z"}"""),
                "skeletons/skeleton_b.json" to artJson("b", """{"id":"snout","d":"L0,0 Z"}"""),
            ),
        )
        val reordered = SkeletonArtValidator.validate("a", checkNotNull(repo.load("skeleton_a.json")), bones)
        assertTrue(reordered.single().contains("Reihenfolge"), reordered.toString())

        val missing = SkeletonArtValidator.validate("b", checkNotNull(repo.load("skeleton_b.json")), bones)
        assertTrue(missing.any { "Zonen [snout]" in it }, missing.toString())
        assertTrue(missing.any { "M/m" in it }, missing.toString())

        val withExtraBone = bones + BoneDefinition("ribs", listOf("head"))
        val art = checkNotNull(repo.load("skeleton_a.json"))
        assertTrue(SkeletonArtValidator.validate("a", art, withExtraBone).any { "'ribs' fehlt" in it })
        assertTrue(SkeletonArtValidator.validate("a", art, bones.take(1)).any { "unbekannter Knochen 'jaw'" in it })
    }

    @Test
    fun validatorRejectsEmptyViewBox() {
        val art = SkeletonArt("x", SkeletonViewBox(0.0, 0.0, 0.0, 10.0), emptyList())
        assertTrue(SkeletonArtValidator.validate("x", art, emptyList()).single().contains("viewBox"))
    }
}
