package de.noelkoeppen.fossilcollector.ui.model3d

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Laedt Knochen-Meshes aus den Assets ([ModelAssets]) einmal und haelt sie fuer die Laufzeit der App
 * im Speicher (AP2-10). Der Hoehlenbaer hat 105 Knochen mit zusammen rund 140 000 Eckpunkten und
 * 275 000 Dreiecken, im Speicher etwa 6,5 MB; die Meshes werden in Skelettansicht, Einzelansicht
 * und spaeter beim Zuordnen und im Sammelbuch wiederverwendet. Fehlende oder ungueltige Dateien
 * ergeben `null` und werden nicht erneut versucht.
 */
class ModelMeshCache private constructor(private val assets: AssetManager) {
    private val meshes = HashMap<String, GlbMesh?>()

    /** Mesh eines Knochens; laedt auf dem IO-Dispatcher, wenn es noch nicht im Speicher ist. */
    suspend fun load(speciesId: String, boneId: String): GlbMesh? {
        val path = ModelAssets.bonePath(speciesId, boneId)
        synchronized(meshes) { if (path in meshes) return meshes[path] }
        val mesh = withContext(Dispatchers.IO) {
            try {
                GlbReader.read(assets.open(path).use { it.readBytes() })
            } catch (e: IOException) {
                Log.w(TAG, "Knochenmodell fehlt: $path", e)
                null
            } catch (e: GlbFormatException) {
                Log.w(TAG, "Knochenmodell ungueltig: $path", e)
                null
            }
        }
        synchronized(meshes) { meshes[path] = mesh }
        return mesh
    }

    /** Meshes mehrerer Knochen derselben Art; fehlende fehlen in der Map. */
    suspend fun loadAll(speciesId: String, boneIds: List<String>): Map<String, GlbMesh> {
        val out = LinkedHashMap<String, GlbMesh>()
        boneIds.forEach { id -> load(speciesId, id)?.let { out[id] = it } }
        return out
    }

    companion object {
        private const val TAG = "ModelMeshCache"

        @Volatile
        private var instance: ModelMeshCache? = null

        /** Eine Instanz je Prozess (die Assets aendern sich zur Laufzeit nicht). */
        fun get(context: Context): ModelMeshCache =
            instance ?: synchronized(this) {
                instance ?: ModelMeshCache(context.applicationContext.assets).also { instance = it }
            }
    }
}
