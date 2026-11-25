package de.noelkoeppen.fossilcollector.ui.model3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Kamera- und Gesten-Mathematik der 3D-Ansicht (AP2-10). */
class OrbitCameraTest {

    /** Ungefaehr die Ausmasse des Hoehlenbaer-Skeletts (Einheiten = Schaedellaenge). */
    private val frame = ModelFrame(2.5f, -0.5f, 0f, 3.6f)

    private fun ndc(camera: OrbitCamera, aspect: Float, x: Float, y: Float, z: Float): FloatArray {
        val m = Mat4.multiply(camera.projectionMatrix(frame, aspect), camera.viewMatrix(frame, aspect))
        val p = Mat4.transform(m, x, y, z)
        return floatArrayOf(p[0] / p[3], p[1] / p[3], p[2] / p[3])
    }

    @Test
    fun yawZeroLooksFromTheLeftSideWithHeadLeft() {
        val camera = OrbitCamera(yaw = 0f, pitch = 0f)
        val eye = camera.eye(frame, 1f)
        assertEquals(frame.centerX, eye[0], EPS)
        assertEquals(frame.centerY, eye[1], EPS)
        assertTrue(eye[2] > frame.centerZ)
        // Kopf (x klein) links, Schwanz (x gross) rechts, oben bleibt oben.
        assertTrue(ndc(camera, 1f, frame.centerX - 1f, frame.centerY, 0f)[0] < 0f)
        assertTrue(ndc(camera, 1f, frame.centerX + 1f, frame.centerY, 0f)[0] > 0f)
        assertTrue(ndc(camera, 1f, frame.centerX, frame.centerY + 1f, 0f)[1] > 0f)
        // Der Mittelpunkt liegt in der Bildmitte.
        val center = ndc(camera, 1f, frame.centerX, frame.centerY, frame.centerZ)
        assertEquals(0f, center[0], EPS)
        assertEquals(0f, center[1], EPS)
    }

    @Test
    fun wholeSphereFitsAtZoomOneInPortraitAndLandscape() {
        listOf(0.5f, 1f, 2f).forEach { aspect ->
            val camera = OrbitCamera(yaw = 37f, pitch = 22f)
            val d = camera.distance(frame, aspect)
            assertTrue(d > frame.radius, "Kamera ausserhalb der Kugel")
            // Punkte der Kugel quer zur Blickrichtung liegen im Bild, Tiefe im Bereich.
            for (dx in listOf(-1f, 1f)) for (dy in listOf(-1f, 1f)) for (dz in listOf(-1f, 1f)) {
                val s = frame.radius / kotlin.math.sqrt(3f)
                val p = ndc(camera, aspect, frame.centerX + dx * s, frame.centerY + dy * s, frame.centerZ + dz * s)
                p.forEach { assertTrue(abs(it) <= 1f + EPS, "Ecke ausserhalb (aspect $aspect): ${p.toList()}") }
            }
        }
    }

    @Test
    fun dragRotatesWithTheFingerAndPitchIsLimited() {
        val start = OrbitCamera(yaw = 0f, pitch = 0f)
        val right = start.rotatedBy(100f, 0f)
        assertEquals(-100f * OrbitCamera.DEGREES_PER_DP, right.yaw, EPS)
        assertEquals(0f, right.pitch, EPS)
        val down = start.rotatedBy(0f, 50f)
        assertEquals(50f * OrbitCamera.DEGREES_PER_DP, down.pitch, EPS)
        assertEquals(OrbitCamera.PITCH_LIMIT, start.rotatedBy(0f, 10_000f).pitch, EPS)
        assertEquals(-OrbitCamera.PITCH_LIMIT, start.rotatedBy(0f, -10_000f).pitch, EPS)
        // Gierwinkel ist unbegrenzt und bleibt in -180..180.
        val spun = (1..50).fold(start) { c, _ -> c.rotatedBy(97f, 0f) }
        assertTrue(spun.yaw in -180f..180f)
        assertEquals(OrbitCamera.wrap(-50 * 97f * OrbitCamera.DEGREES_PER_DP), spun.yaw, 1e-2f)
    }

    @Test
    fun zoomIsClampedAndMovesCameraCloser() {
        val start = OrbitCamera()
        assertEquals(OrbitCamera.MIN_ZOOM, start.zoomedBy(0.2f).zoom, EPS)
        assertEquals(OrbitCamera.MAX_ZOOM, start.zoomedBy(100f).zoom, EPS)
        assertEquals(2f, start.zoomedBy(2f).zoom, EPS)
        assertEquals(start.distance(frame, 1f) / 2f, start.zoomedBy(2f).distance(frame, 1f), EPS)
        assertSame(start, start.zoomedBy(Float.NaN))
        assertSame(start, start.zoomedBy(0f))
        // Bei hoechstem Zoom kann die Kamera in der Kugel stehen: near bleibt positiv.
        val close = start.zoomedBy(100f)
        val center = ndc(close, 1f, frame.centerX, frame.centerY, frame.centerZ)
        assertTrue(abs(center[2]) <= 1f)
    }

    @Test
    fun frameFromMeshesUsesBoundingBox() {
        val a = GlbMesh(floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f), FloatArray(6), intArrayOf(), null)
        val b = GlbMesh(floatArrayOf(0f, -1f, 0f, 0f, 1f, 0f), FloatArray(6), intArrayOf(), null)
        val f = ModelFrame.of(listOf(a, b))
        assertEquals(1f, f.centerX, EPS)
        assertEquals(0f, f.centerY, EPS)
        assertEquals(kotlin.math.sqrt(8f) / 2f, f.radius, EPS)
        assertEquals(ModelFrame(0f, 0f, 0f, 1f), ModelFrame.of(emptyList()))
    }

    @Test
    fun perspectiveMapsNearAndFarToClipRange() {
        val p = Mat4.perspective(Math.toRadians(60.0).toFloat(), 1f, 1f, 10f)
        val near = Mat4.transform(p, 0f, 0f, -1f)
        val far = Mat4.transform(p, 0f, 0f, -10f)
        assertEquals(-1f, near[2] / near[3], EPS)
        assertEquals(1f, far[2] / far[3], EPS)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
