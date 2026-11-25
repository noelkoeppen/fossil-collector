package de.noelkoeppen.fossilcollector.ui.model3d

import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 4x4-Matrizen fuer den Renderer, spaltenweise wie in OpenGL (`m[spalte * 4 + zeile]`).
 * Eigene kleine Rechnung statt `android.opengl.Matrix`, damit Kamera und Projektion in
 * JVM-Unit-Tests pruefbar sind.
 */
object Mat4 {

    fun identity(): FloatArray = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f }

    /** `a * b` (erst `b`, dann `a` anwenden). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    /** Perspektive wie `gluPerspective`: Blick entlang -z, Tiefe `near..far` auf -1..1. */
    fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovYRadians / 2f)
        return FloatArray(16).also {
            it[0] = f / aspect
            it[5] = f
            it[10] = (far + near) / (near - far)
            it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }

    /** Ansicht von [eye] auf [target] mit Oben-Richtung [up] (wie `gluLookAt`). */
    fun lookAt(eye: FloatArray, target: FloatArray, up: FloatArray): FloatArray {
        val f = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val s = normalize(cross(f, up))
        val u = cross(s, f)
        return FloatArray(16).also {
            it[0] = s[0]; it[4] = s[1]; it[8] = s[2]
            it[1] = u[0]; it[5] = u[1]; it[9] = u[2]
            it[2] = -f[0]; it[6] = -f[1]; it[10] = -f[2]
            it[12] = -(s[0] * eye[0] + s[1] * eye[1] + s[2] * eye[2])
            it[13] = -(u[0] * eye[0] + u[1] * eye[1] + u[2] * eye[2])
            it[14] = f[0] * eye[0] + f[1] * eye[1] + f[2] * eye[2]
            it[15] = 1f
        }
    }

    /** Punkt `(x, y, z, 1)` transformieren; Ergebnis `x, y, z, w` ohne Division durch `w`. */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = FloatArray(4) { row ->
        m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row]
    }

    fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (len > 0f) floatArrayOf(v[0] / len, v[1] / len, v[2] / len) else floatArrayOf(0f, 0f, 0f)
    }
}
