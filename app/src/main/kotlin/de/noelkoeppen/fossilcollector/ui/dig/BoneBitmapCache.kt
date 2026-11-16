package de.noelkoeppen.fossilcollector.ui.dig

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Laedt Knochenbilder aus den Assets ([BoneImageAssets]) einmal und haelt sie im Speicher (AP2-07).
 * Eine Ausgrabung hat nur wenige Knochen (bis etwa zwei Dutzend), die Bilder sind klein (der
 * Oberschenkel 426 x 104 Pixel), deshalb wird nichts verdraengt. Fehlende Bilder ergeben `null` und
 * werden nicht erneut versucht.
 */
class BoneBitmapCache(private val assets: AssetManager) {
    private val images = HashMap<String, ImageBitmap?>()

    /** Bild eines Knochens; laedt auf dem IO-Dispatcher, wenn es noch nicht im Speicher ist. */
    suspend fun load(speciesId: String, boneId: String): ImageBitmap? {
        val path = BoneImageAssets.imagePath(speciesId, boneId)
        synchronized(images) { if (path in images) return images[path] }
        val image = withContext(Dispatchers.IO) {
            try {
                assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            } catch (e: IOException) {
                Log.w(TAG, "Knochenbild fehlt: $path", e)
                null
            }
        }
        synchronized(images) { images[path] = image }
        return image
    }

    private companion object {
        const val TAG = "BoneBitmapCache"
    }
}
