package de.noelkoeppen.fossilcollector.ui.model3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * Was der Renderer zeichnen soll; unveraenderlich, wird als Ganzes ausgetauscht.
 *
 * @property meshes Netze je Knochen-ID (CPU-Kopie bleibt erhalten: Neuaufbau nach Kontextverlust,
 *   spaeter Treffertest beim Zuordnen)
 * @property looks Aussehen je Knochen; Knochen ohne Eintrag werden nicht gezeichnet
 * @property background Hintergrund `r, g, b`
 */
class RenderScene(
    val meshes: Map<String, GlbMesh>,
    val looks: Map<String, BoneLook>,
    val frame: ModelFrame,
    val camera: OrbitCamera,
    val background: FloatArray,
)

/**
 * Eigener kleiner OpenGL-ES-3.0-Renderer fuer die Knochen-Meshes (AP2-10, Kapitel 8).
 *
 * Ein Vertex-Array je Knochen (Position und Normale, Indizes als `unsigned int`), ein Draw-Call je
 * sichtbarem Knochen mit seiner Farbe als Uniform. Beleuchtung wie bei den 2D-Knochenbildern
 * (`tools/models3d/bone_images.py`): Licht aus Blickrichtung, leicht von links oben, Umgebungs-,
 * diffuser und schwacher Glanzanteil, damit Knochen in 2D und 3D gleich wirken.
 *
 * Laeuft auf dem GL-Thread von [GLSurfaceView]; [update] darf von jedem Thread kommen.
 */
class ModelRenderer : GLSurfaceView.Renderer {

    @Volatile
    private var scene: RenderScene? = null

    /** Hochgeladene Netze (nur GL-Thread). */
    private val gpu = HashMap<String, GpuMesh>()
    private var program = 0
    private var uViewProj = 0
    private var uView = 0
    private var uColor = 0
    private var uLight = 0
    private var uShade = 0
    private var aspect = 1f

    fun update(newScene: RenderScene) {
        scene = newScene
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Neuer Kontext: alte GL-Namen sind ungueltig, alles neu hochladen.
        gpu.clear()
        program = buildProgram()
        uViewProj = GLES30.glGetUniformLocation(program, "uViewProj")
        uView = GLES30.glGetUniformLocation(program, "uView")
        uColor = GLES30.glGetUniformLocation(program, "uColor")
        uLight = GLES30.glGetUniformLocation(program, "uLight")
        uShade = GLES30.glGetUniformLocation(program, "uShade")
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // Kein Back-Face-Culling: robust gegen uneinheitliche Dreiecksorientierung, die Netze sind klein.
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = scene
        val bg = s?.background ?: BLACK
        GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (s == null || program == 0) return
        syncMeshes(s.meshes)

        val view = s.camera.viewMatrix(s.frame, aspect)
        val viewProj = Mat4.multiply(s.camera.projectionMatrix(s.frame, aspect), view)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES30.glUniformMatrix4fv(uView, 1, false, view, 0)
        GLES30.glUniform3fv(uLight, 1, ModelShading.LIGHT, 0)
        GLES30.glUniform3f(uShade, ModelShading.AMBIENT, ModelShading.DIFFUSE, ModelShading.SPECULAR)
        for ((boneId, look) in s.looks) {
            if (!look.visible) continue
            val mesh = gpu[boneId] ?: continue
            GLES30.glUniform3f(uColor, look.red, look.green, look.blue)
            GLES30.glBindVertexArray(mesh.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }
        GLES30.glBindVertexArray(0)
    }

    /** Laedt neue Netze hoch und gibt nicht mehr benoetigte frei (Vergleich ueber die Objekt-Identitaet). */
    private fun syncMeshes(meshes: Map<String, GlbMesh>) {
        val stale = gpu.filter { (id, g) -> meshes[id] !== g.source }
        stale.forEach { (id, g) ->
            g.delete()
            gpu.remove(id)
        }
        meshes.forEach { (id, mesh) -> if (id !in gpu) gpu[id] = GpuMesh.upload(mesh) }
    }

    private class GpuMesh(val source: GlbMesh, val vao: Int, val vbo: Int, val ibo: Int, val indexCount: Int) {
        fun delete() {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        }

        companion object {
            fun upload(mesh: GlbMesh): GpuMesh {
                val ids = IntArray(1)
                GLES30.glGenVertexArrays(1, ids, 0)
                val vao = ids[0]
                val buffers = IntArray(2)
                GLES30.glGenBuffers(2, buffers, 0)
                GLES30.glBindVertexArray(vao)

                val vertices = ByteBuffer.allocateDirect(mesh.vertexCount * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (v in 0 until mesh.vertexCount) {
                    vertices.put(mesh.positions, v * 3, 3)
                    vertices.put(mesh.normals, v * 3, 3)
                }
                vertices.position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertexCount * STRIDE, vertices, GLES30.GL_STATIC_DRAW)
                GLES30.glEnableVertexAttribArray(ATTR_POSITION)
                GLES30.glVertexAttribPointer(ATTR_POSITION, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
                GLES30.glEnableVertexAttribArray(ATTR_NORMAL)
                GLES30.glVertexAttribPointer(ATTR_NORMAL, 3, GLES30.GL_FLOAT, false, STRIDE, NORMAL_OFFSET)

                val indices = ByteBuffer.allocateDirect(mesh.indices.size * INT_SIZE).order(ByteOrder.nativeOrder()).asIntBuffer()
                indices.put(mesh.indices).position(0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * INT_SIZE, indices, GLES30.GL_STATIC_DRAW)

                GLES30.glBindVertexArray(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
                return GpuMesh(mesh, vao, buffers[0], buffers[1], mesh.indices.size)
            }
        }
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vs == 0 || fs == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, ATTR_POSITION, "aPos")
        GLES30.glBindAttribLocation(p, ATTR_NORMAL, "aNormal")
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader-Programm: ${GLES30.glGetProgramInfoLog(p)}")
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * EGL-Konfiguration fuer OpenGL ES 3.0 mit Tiefenpuffer, bevorzugt mit 4-fachem Multisampling
     * fuer glatte Kanten; ohne Multisampling bzw. mit 16-Bit-Tiefe als Rueckfall.
     */
    class ConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            for (attributes in CANDIDATES) {
                val found = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, attributes, configs, 1, found) && found[0] > 0) {
                    configs[0]?.let { return it }
                }
            }
            throw IllegalStateException("Keine EGL-Konfiguration fuer OpenGL ES 3.0")
        }

        private companion object {
            const val EGL_RENDERABLE_TYPE = 0x3040
            const val EGL_OPENGL_ES3_BIT = 0x40
            const val MSAA_SAMPLES = 4

            fun attributes(depth: Int, samples: Int): IntArray {
                val base = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                )
                val msaa = if (samples > 0) intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples) else intArrayOf()
                return base + msaa + intArrayOf(EGL10.EGL_NONE)
            }

            val CANDIDATES = listOf(
                attributes(24, MSAA_SAMPLES),
                attributes(16, MSAA_SAMPLES),
                attributes(24, 0),
                attributes(16, 0),
            )
        }
    }

    private companion object {
        const val TAG = "ModelRenderer"
        const val ATTR_POSITION = 0
        const val ATTR_NORMAL = 1
        const val FLOAT_SIZE = 4
        const val INT_SIZE = 4
        const val STRIDE = 6 * FLOAT_SIZE
        const val NORMAL_OFFSET = 3 * FLOAT_SIZE
        val BLACK = floatArrayOf(0f, 0f, 0f)

        const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
uniform mat4 uViewProj;
uniform mat4 uView;
out vec3 vNormal;
void main() {
    vNormal = mat3(uView) * aNormal;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""

        // Normale und Licht in Kameraraum (rechts, oben, zur Kamera) wie in bone_images.py.
        const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 vNormal;
uniform vec3 uColor;
uniform vec3 uLight;
uniform vec3 uShade;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    float lambert = max(dot(n, uLight), 0.0);
    vec3 h = normalize(uLight + vec3(0.0, 0.0, 1.0));
    float spec = pow(max(dot(n, h), 0.0), 16.0);
    vec3 c = (uShade.x + uShade.y * lambert) * uColor + uShade.z * spec;
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}

/**
 * Beleuchtung der 3D-Ansicht: dieselben Werte wie `DEFAULTS` in `tools/models3d/bone_images.py`
 * (`light`, `ambient`, `diffuse`, `specular`), damit ein Knochen in der Grabung (2D-Bild) und am
 * Skelett gleich aussieht. Darstellungskonstanten, keine Spielwerte. `ModelShadingTest` prueft,
 * dass die Werte zum Skript passen.
 */
object ModelShading {
    /** Lichtrichtung im Kameraraum (rechts, oben, zur Kamera), normiert. */
    val LIGHT: FloatArray = Mat4.normalize(floatArrayOf(-0.35f, 0.45f, 0.82f))
    const val AMBIENT = 0.32f
    const val DIFFUSE = 0.68f
    const val SPECULAR = 0.10f
}
