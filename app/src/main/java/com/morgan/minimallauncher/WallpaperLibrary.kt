package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Every picture the launcher can show: the 15 bundled landscape photos
 * (grouped into categories), and "My Photos" uploaded from the iPhone
 * remote. Each profile picks what its home screen shows.
 */
object WallpaperLibrary {

    sealed class Photo {
        abstract val place: String
        data class Bundled(val number: Int, val res: Int, val front: Int, override val place: String) : Photo()
        data class Local(val file: File, override val place: String) : Photo()
    }

    class Category(val id: String, val title: String, val numbers: List<Int>)

    val bundled: List<Photo.Bundled> = listOf(
        Photo.Bundled(1, R.drawable.wp_01, 0, "Matterhorn, Switzerland"),
        Photo.Bundled(2, R.drawable.wp_02, 0, "Reine, Norway"),
        Photo.Bundled(3, R.drawable.wp_03, R.drawable.wp_03_front, "Lake Louise, Canada"),
        Photo.Bundled(4, R.drawable.wp_04, 0, "Schwabacher Landing, Wyoming"),
        Photo.Bundled(5, R.drawable.wp_05, 0, "Jökulsárlón, Iceland"),
        Photo.Bundled(6, R.drawable.wp_06, R.drawable.wp_06_front, "Glacier National Park, Montana"),
        Photo.Bundled(7, R.drawable.wp_07, 0, "Baker Lake, Banff"),
        Photo.Bundled(8, R.drawable.wp_08, 0, "Maroon Bells, Colorado"),
        Photo.Bundled(9, R.drawable.wp_09, R.drawable.wp_09_front, "Maligne Lake, Jasper"),
        Photo.Bundled(10, R.drawable.wp_10, 0, "Grand Teton, Wyoming"),
        Photo.Bundled(11, R.drawable.wp_11, 0, "General Carrera Lake, Chile"),
        Photo.Bundled(12, R.drawable.wp_12, R.drawable.wp_12_front, "Lake Geneva, France"),
        Photo.Bundled(13, R.drawable.wp_13, R.drawable.wp_13_front, "Lake Louise, Canada"),
        Photo.Bundled(14, R.drawable.wp_14, 0, "Øksfjorden, Norway"),
        Photo.Bundled(15, R.drawable.wp_15, 0, "Sandy Lake, Canada"),
    )

    val categories = listOf(
        Category("mountains", "Mountains", listOf(1, 4, 6, 8, 10)),
        Category("lakes", "Lakes", listOf(3, 7, 9, 11, 13)),
        Category("fjords", "Fjords & Ice", listOf(2, 5, 14)),
        Category("twilight", "Twilight", listOf(1, 12, 15)),
    )

    fun albumDir(c: Context): File = File(c.filesDir, "album").apply { mkdirs() }

    fun album(c: Context): List<Photo.Local> =
        (albumDir(c).listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .map { Photo.Local(it, "My Photos") }

    /** What this profile's home screen rotates through, first photo first. */
    fun forProfile(c: Context): List<Photo> {
        val choice = ProfileManager.getWallpaper(c)
        val list: List<Photo> = when {
            choice == "album" -> album(c)
            choice.startsWith("photo:") -> bundled.filter { it.number == choice.removePrefix("photo:").toIntOrNull() }
            choice.startsWith("cat:") -> categories.firstOrNull { it.id == choice.removePrefix("cat:") }
                ?.numbers?.mapNotNull { n -> bundled.firstOrNull { it.number == n } }.orEmpty()
            else -> listOf(bundled[2]) + bundled.filter { it.number != 3 }
        }
        return list.ifEmpty { bundled }
    }

    fun describe(c: Context, choice: String = ProfileManager.getWallpaper(c)): String = when {
        choice == "album" -> "My Photos"
        choice.startsWith("photo:") -> bundled.firstOrNull { it.number == choice.removePrefix("photo:").toIntOrNull() }?.place ?: "One Photo"
        choice.startsWith("cat:") -> categories.firstOrNull { it.id == choice.removePrefix("cat:") }?.title ?: "All Photos"
        else -> "All Photos"
    }

    /** Full-size decode, scaled down for very large uploads. Null if it can't be read. */
    fun decode(c: Context, photo: Photo, maxWidth: Int = 1920): Bitmap? = try {
        when (photo) {
            is Photo.Bundled -> BitmapFactory.decodeResource(c.resources, photo.res)
            is Photo.Local -> decodeFile(photo.file, maxWidth)
        }
    } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }

    fun decodeFile(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun decodeFront(c: Context, photo: Photo): Bitmap? =
        if (photo is Photo.Bundled && photo.front != 0) try {
            BitmapFactory.decodeResource(c.resources, photo.front)
        } catch (e: OutOfMemoryError) { null } else null
}
