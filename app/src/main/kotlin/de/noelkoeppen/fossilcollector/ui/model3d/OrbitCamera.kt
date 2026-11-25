package de.noelkoeppen.fossilcollector.ui.model3d

import androidx.compose.runtime.Immutable
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Umgebende Kugel der angezeigten Knochen: Drehpunkt und Groesse fuer die Kamera.
 * Gerechnet ueber alle Knochen der Ansicht (auch ausgeblendete), damit die Kamera nicht springt,
 * wenn Knochen ein- oder ausgeblendet werden.
 */
@Immutable
data class ModelFrame(val centerX: Float, val centerY: Float, val centerZ: Float, val radius: Float) {
    companion object {
        /** Mittelpunkt der Bounding-Box und halbe Diagonale; leere Liste ergibt eine Einheitskugel. */
        fun of(meshes: Collection<GlbMesh>): ModelFrame {
            if (meshes.isEmpty()) return ModelFrame(0f, 0f, 0f, 1f)
            val lo = FloatArray(3) { axis -> meshes.minOf { it.min[axis] } }
            val hi = FloatArray(3) { axis -> meshes.maxOf { it.max[axis] } }
            val dx = hi[0] - lo[0]
            val dy = hi[1] - lo[1]
            val dz = hi[2] - lo[2]
            val radius = sqrt(dx * dx + dy * dy + dz * dz) / 2f
            return ModelFrame((lo[0] + hi[0]) / 2f, (lo[1] + hi[1]) / 2f, (lo[2] + hi[2]) / 2f, max(radius, MIN_RADIUS))
        }

        private const val MIN_RADIUS = 1e-3f
    }
}

/**
 * Kamera, die um den Mittelpunkt des Modells kreist (AP2-10). Unveraenderlich; Gesten liefern
 * eine neue Kamera. Winkel in Grad.
 *
 * Koordinaten des Modells (`art/models3d/<speciesId>/README.md`): x nach hinten, y nach oben,
 * z zur linken Seite des Tieres. Gierwinkel 0 blickt von der linken Seite (Kamera auf +z), der Kopf
 * liegt dann links im Bild; positive Gierwinkel wandern zum Schwanz, positive Nickwinkel nach oben.
 *
 * @property zoom 1 = ganzes Modell passt ins Bild, groesser = naeher
 */
@Immutable
data class OrbitCamera(
    val yaw: Float = INITIAL_YAW,
    val pitch: Float = INITIAL_PITCH,
    val zoom: Float = 1f,
) {
    /**
     * Drehen mit einem Finger: Das Modell folgt dem Finger (nach rechts ziehen dreht die
     * Vorderseite nach rechts, nach unten ziehen kippt die Oberseite zum Betrachter).
     * Bewegung in dp, damit es auf jeder Bildschirmdichte gleich wirkt.
     */
    fun rotatedBy(dxDp: Float, dyDp: Float): OrbitCamera = copy(
        yaw = wrap(yaw - dxDp * DEGREES_PER_DP),
        pitch = (pitch + dyDp * DEGREES_PER_DP).coerceIn(-PITCH_LIMIT, PITCH_LIMIT),
    )

    /** Zoom mit zwei Fingern: Faktor der Fingerspreizung, begrenzt auf [MIN_ZOOM]..[MAX_ZOOM]. */
    fun zoomedBy(factor: Float): OrbitCamera =
        if (factor.isFinite() && factor > 0f) copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)) else this

    /** Abstand der Kamera zum Mittelpunkt, bei dem die Kugel bei Zoom 1 gerade ins Bild passt. */
    fun fitDistance(frame: ModelFrame, aspect: Float): Float {
        val halfY = FOV_Y_RADIANS / 2f
        val halfX = atan(tan(halfY) * aspect)
        return frame.radius / sin(min(halfY, halfX))
    }

    fun distance(frame: ModelFrame, aspect: Float): Float = fitDistance(frame, aspect) / zoom

    /** Kameraposition `x, y, z`. */
    fun eye(frame: ModelFrame, aspect: Float): FloatArray {
        val d = distance(frame, aspect)
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        return floatArrayOf(
            frame.centerX + (d * cos(p) * sin(y)).toFloat(),
            frame.centerY + (d * sin(p)).toFloat(),
            frame.centerZ + (d * cos(p) * cos(y)).toFloat(),
        )
    }

    fun viewMatrix(frame: ModelFrame, aspect: Float): FloatArray =
        Mat4.lookAt(eye(frame, aspect), floatArrayOf(frame.centerX, frame.centerY, frame.centerZ), UP)

    /**
     * Perspektive mit Tiefenbereich um die Kugel: `near` vor der Kugel (mindestens ein kleiner Teil
     * des Radius, falls die Kamera beim Zoomen in die Kugel kommt), `far` hinter ihr.
     */
    fun projectionMatrix(frame: ModelFrame, aspect: Float): FloatArray {
        val d = distance(frame, aspect)
        val near = max(d - frame.radius, frame.radius * NEAR_MIN_FRACTION)
        val far = d + frame.radius
        return Mat4.perspective(FOV_Y_RADIANS, aspect, near, far)
    }

    companion object {
        /** Vertikaler Blickwinkel: eher eng, damit das Skelett kaum verzerrt wirkt. */
        const val FOV_Y_DEGREES = 30f
        val FOV_Y_RADIANS = Math.toRadians(FOV_Y_DEGREES.toDouble()).toFloat()

        /**
         * Zoom-Grenzen wie beim Graben, Freilegen und Bergen (1 bis 4, Kapitel 3 und 5): 1 zeigt das
         * ganze Modell, weiter heraus bringt nichts; mehr als 4 waere ohne Verschieben nicht sinnvoll,
         * weil die Kamera um die Mitte kreist (Verschieben kommt bei Bedarf mit AP2-11).
         */
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f

        /** Grad Drehung je dp Fingerweg: eine Bildschirmbreite (rund 400 dp) dreht etwa 160 Grad. */
        const val DEGREES_PER_DP = 0.4f

        /**
         * Nickwinkel bleibt unter 90 Grad, damit "oben" eindeutig bleibt und die Ansicht nicht ueber
         * den Pol kippt; 80 Grad erlaubt trotzdem fast die Draufsicht und den Blick von unten.
         */
        const val PITCH_LIMIT = 80f

        /** Startansicht: schraeg von vorn links und leicht von oben, Kopf links. */
        const val INITIAL_YAW = -30f
        const val INITIAL_PITCH = 15f

        private const val NEAR_MIN_FRACTION = 0.01f
        private val UP = floatArrayOf(0f, 1f, 0f)

        /** Winkel auf -180..180 halten (Gierwinkel ist unbegrenzt drehbar). */
        internal fun wrap(degrees: Float): Float {
            var d = degrees % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
