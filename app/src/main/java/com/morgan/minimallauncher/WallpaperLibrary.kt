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
        /** A high-resolution photo from Wikimedia Commons' Featured Pictures, downloaded once and kept on the TV. */
        data class Online(val id: String, val url: String, override val place: String, val credit: String) : Photo()
    }

    class Category(val id: String, val title: String, val numbers: List<Int>)

    val bundled: List<Photo.Bundled> = listOf(
        Photo.Bundled(1, R.drawable.wp_01, 0, "Matterhorn, Switzerland"),
        Photo.Bundled(2, R.drawable.wp_02, 0, "Reine, Norway"),
        Photo.Bundled(3, R.drawable.wp_03, 0, "Lake Louise, Canada"),
        Photo.Bundled(4, R.drawable.wp_04, 0, "Schwabacher Landing, Wyoming"),
        Photo.Bundled(5, R.drawable.wp_05, 0, "Jökulsárlón, Iceland"),
        Photo.Bundled(6, R.drawable.wp_06, 0, "Glacier National Park, Montana"),
        Photo.Bundled(7, R.drawable.wp_07, 0, "Baker Lake, Banff"),
        Photo.Bundled(8, R.drawable.wp_08, 0, "Maroon Bells, Colorado"),
        Photo.Bundled(9, R.drawable.wp_09, 0, "Maligne Lake, Jasper"),
        Photo.Bundled(10, R.drawable.wp_10, 0, "Grand Teton, Wyoming"),
        Photo.Bundled(11, R.drawable.wp_11, 0, "General Carrera Lake, Chile"),
        Photo.Bundled(12, R.drawable.wp_12, 0, "Lake Geneva, France"),
        Photo.Bundled(13, R.drawable.wp_13, 0, "Lake Louise, Canada"),
        Photo.Bundled(14, R.drawable.wp_14, 0, "Øksfjorden, Norway"),
        Photo.Bundled(15, R.drawable.wp_15, 0, "Sandy Lake, Canada"),
    )

    /**
     * The home screen's own set: Wikimedia Commons Featured Pictures (the community's top-rated photos, all free to
     * use with credit), chosen for clear skies where the clock sits. 1920px each, fetched the first time they're shown.
     */
    val hd: List<Photo.Online> = listOf(
        Photo.Online("hd01", "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6c/1_lake_louise_pano_2019.jpg/1920px-1_lake_louise_pano_2019.jpg", "Lake Louise, Canada", "Chensiyuan · CC BY-SA 4.0"),
        Photo.Online("hd02", "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/99/2011-08-01_10-31-42_Switzerland_Segl-Maria.jpg/1920px-2011-08-01_10-31-42_Switzerland_Segl-Maria.jpg", "Lake Sils, Switzerland", "Hansueli Krapf · CC BY-SA 3.0"),
        Photo.Online("hd03", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/45/Afternoon_at_Tennfjorden%2C_Raftsundet%2C_Hinn%C3%B8ya%2C_Norway%2C_2015_September.jpg/1920px-Afternoon_at_Tennfjorden%2C_Raftsundet%2C_Hinn%C3%B8ya%2C_Norway%2C_2015_September.jpg", "Raftsundet, Norway", "Ximonic (Simo Räsänen) · CC BY-SA 3.0"),
        Photo.Online("hd04", "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/eb/Aoraki_-_Mt_Cook%2C_Aoraki_-_Mount_Cook_National_Park%2C_New_Zealand.jpg/1920px-Aoraki_-_Mt_Cook%2C_Aoraki_-_Mount_Cook_National_Park%2C_New_Zealand.jpg", "Aoraki / Mount Cook, New Zealand", "Michal Klajban · CC BY-SA 4.0"),
        Photo.Online("hd05", "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f0/Cirrus_front_over_Austnesfjorden%2C_Austv%C3%A5g%C3%B8ya%2C_Lofoten%2C_Norway%2C_2015_April.jpg/1920px-Cirrus_front_over_Austnesfjorden%2C_Austv%C3%A5g%C3%B8ya%2C_Lofoten%2C_Norway%2C_2015_April.jpg", "Austnesfjorden, Lofoten, Norway", "Ximonic (Simo Räsänen) · CC BY-SA 3.0"),
        Photo.Online("hd06", "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/Fiordo_de_Geiranger_desde_Flydalsjuvet%2C_Noruega%2C_2019-09-07%2C_DD_59.jpg/1920px-Fiordo_de_Geiranger_desde_Flydalsjuvet%2C_Noruega%2C_2019-09-07%2C_DD_59.jpg", "Geirangerfjord, Norway", "Diego Delso · CC BY-SA 4.0"),
        Photo.Online("hd07", "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/From_Tennfjorden_towards_Raftsundet%2C_Hinn%C3%B8ya%2C_Norway%2C_2015_September_-_2.jpg/1920px-From_Tennfjorden_towards_Raftsundet%2C_Hinn%C3%B8ya%2C_Norway%2C_2015_September_-_2.jpg", "Tennfjorden, Norway", "Ximonic (Simo Räsänen) · CC BY-SA 3.0"),
        Photo.Online("hd08", "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Gentau_Pic_du_Midi_Ossau.jpg/1920px-Gentau_Pic_du_Midi_Ossau.jpg", "Pic du Midi d'Ossau, France", "Myrabella · CC BY-SA 3.0"),
        Photo.Online("hd09", "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/68/Himalayas%2C_Cholatse%2C_Nepal.jpg/1920px-Himalayas%2C_Cholatse%2C_Nepal.jpg", "Cholatse, Nepal", "Vyacheslav Argenberg · CC BY 4.0"),
        Photo.Online("hd10", "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b5/Karst_peaks_with_sea_of_clouds_at_sunrise%2C_South_view_from_the_top_of_Mount_Nam_Xay%2C_Vang_Vieng%2C_Laos.jpg/1920px-Karst_peaks_with_sea_of_clouds_at_sunrise%2C_South_view_from_the_top_of_Mount_Nam_Xay%2C_Vang_Vieng%2C_Laos.jpg", "Karst peaks above the clouds, Laos", "Basile Morin · CC BY-SA 4.0"),
        Photo.Online("hd11", "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ea/Kirkjufell%2C_Iceland%2C_20240714_1631_0713.jpg/1920px-Kirkjufell%2C_Iceland%2C_20240714_1631_0713.jpg", "Kirkjufell, Iceland", "Jakub Hałun · CC BY 4.0"),
        Photo.Online("hd12", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4b/Lac_de_Cap-de-Long_%2822%29.jpg/1920px-Lac_de_Cap-de-Long_%2822%29.jpg", "Lac de Cap-de-Long, France", "Tournasol7 · CC BY 4.0"),
        Photo.Online("hd13", "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9d/Lake_Mystery%2C_Canterbury%2C_New_Zealand_05.jpg/1920px-Lake_Mystery%2C_Canterbury%2C_New_Zealand_05.jpg", "Lake Mystery, New Zealand", "Michal Klajban · CC BY-SA 4.0"),
        Photo.Online("hd14", "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e2/Lake_Tekapo_01.jpg/1920px-Lake_Tekapo_01.jpg", "Lake Tekapo, New Zealand", "Krzysztof Golik · CC BY-SA 4.0"),
        Photo.Online("hd15", "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/73/Lake_Vuoksa_1.jpg/1920px-Lake_Vuoksa_1.jpg", "Lake Vuoksa, Karelia", "Dmitry A. Mottl · CC BY-SA 3.0"),
        Photo.Online("hd16", "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/21/Matterhorn%2C_March_2019_%2801%29.jpg/1920px-Matterhorn%2C_March_2019_%2801%29.jpg", "The Matterhorn, Switzerland", "Liridon · CC BY-SA 4.0"),
        Photo.Online("hd17", "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/21/Matterhorn_sunset_2016_%28Unsplash%29.jpg/1920px-Matterhorn_sunset_2016_%28Unsplash%29.jpg", "The Matterhorn at sunset", "Sam Ferrara samferrara · CC0"),
        Photo.Online("hd18", "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7b/Padar_Island%2C_Komodo_National_Park%2C_Indonesia%2C_20250822_0929_2659.jpg/1920px-Padar_Island%2C_Komodo_National_Park%2C_Indonesia%2C_20250822_0929_2659.jpg", "Padar Island, Indonesia", "Jakub Hałun · CC BY 4.0"),
        Photo.Online("hd19", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4f/Pointe_de_Veret_from_Lac_de_Veret_%285%29.jpg/1920px-Pointe_de_Veret_from_Lac_de_Veret_%285%29.jpg", "Lac de Vérét, France", "Tournasol7 · CC BY 4.0"),
        Photo.Online("hd20", "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d7/Princetown_%28AU%29%2C_Port_Campbell_National_Park%2C_Twelve_Apostles_--_2019_--_0969.jpg/1920px-Princetown_%28AU%29%2C_Port_Campbell_National_Park%2C_Twelve_Apostles_--_2019_--_0969.jpg", "Twelve Apostles, Australia", "Dietmar Rabich · CC BY-SA 4.0"),
        Photo.Online("hd21", "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/32/Silver_Lake_Mammoth_September_2016_001.jpg/1920px-Silver_Lake_Mammoth_September_2016_001.jpg", "Silver Lake, California", "King of Hearts · CC BY-SA 4.0"),
        Photo.Online("hd22", "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/34/Square_plot_of_a_green_paddy_field_and_water_reflection_of_mountains_with_mist_and_blue_sky%2C_Vang_Vieng%2C_Laos.jpg/1920px-Square_plot_of_a_green_paddy_field_and_water_reflection_of_mountains_with_mist_and_blue_sky%2C_Vang_Vieng%2C_Laos.jpg", "Rice fields and karst peaks, Laos", "Basile Morin · CC BY-SA 4.0"),
        Photo.Online("hd23", "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/00/Sweet_Bread_Mountain.jpg/1920px-Sweet_Bread_Mountain.jpg", "Sugarloaf Mountain, Rio de Janeiro", "Donatas Dabravolskas · CC BY-SA 4.0"),
        Photo.Online("hd24", "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/25/Vandans_L%C3%BCnersee_Vogelperspektive_W.jpg/1920px-Vandans_L%C3%BCnersee_Vogelperspektive_W.jpg", "Lünersee, Austria", "Herbert Heim · CC BY-SA 4.0"),
        Photo.Online("hd25", "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cf/Verkhoyansk_Range%2C_Republic_of_Sakha_%28Yakutia%29%2C_Russia_-_2019.jpg/1920px-Verkhoyansk_Range%2C_Republic_of_Sakha_%28Yakutia%29%2C_Russia_-_2019.jpg", "Verkhoyansk Range, Siberia", "Ilya Varlamov · CC BY-SA 3.0"),
        Photo.Online("hd26", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4e/Water_reflection_of_karst_mountains%2C_wooden_hut%2C_trees_and_colorful_clouds_at_sunset_with_green_paddy_fields%2C_Vang_Vieng%2C_Laos.jpg/1920px-Water_reflection_of_karst_mountains%2C_wooden_hut%2C_trees_and_colorful_clouds_at_sunset_with_green_paddy_fields%2C_Vang_Vieng%2C_Laos.jpg", "Rice fields and karst peaks, Laos", "Basile Morin · CC BY-SA 4.0"),
    )

    fun hdFile(c: Context, p: Photo.Online): File = File(File(c.filesDir, "wp_hd").apply { mkdirs() }, "${p.id}.jpg")

    /** The photo's file on the TV, downloading it first if needed. Null when it can't be fetched (offline). */
    fun hdReady(c: Context, p: Photo.Online): File? {
        val f = hdFile(c, p)
        if (f.exists() && f.length() > 50_000) return f
        return if (Net.download(p.url, f) && f.length() > 50_000) f else null
    }

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
    fun forProfile(c: Context): List<Photo> = photosFor(c, ProfileManager.getWallpaper(c))

    /** The photos a wallpaper choice ("all", "cat:x", "photo:n", "album") stands for. */
    fun photosFor(c: Context, choice: String): List<Photo> {
        val list: List<Photo> = when {
            choice == "album" -> album(c)
            choice.startsWith("photo:hd") -> hd.filter { it.id == choice.removePrefix("photo:") }
            choice.startsWith("photo:") -> bundled.filter { it.number == choice.removePrefix("photo:").toIntOrNull() }
            choice.startsWith("cat:") -> categories.firstOrNull { it.id == choice.removePrefix("cat:") }
                ?.numbers?.mapNotNull { n -> bundled.firstOrNull { it.number == n } }.orEmpty()
            // All Photos: the HD set (the bundled ones stand in while offline).
            else -> hd
        }
        return list.ifEmpty { bundled }
    }

    /** "Place · Photographer · licence" for the credit line (CC BY / BY-SA ask for it). */
    fun credit(p: Photo): String = (p as? Photo.Online)?.credit.orEmpty()

    fun describe(c: Context, choice: String = ProfileManager.getWallpaper(c)): String = when {
        choice == "album" -> "My Photos"
        choice.startsWith("photo:hd") -> hd.firstOrNull { it.id == choice.removePrefix("photo:") }?.place ?: "One Photo"
        choice.startsWith("photo:") -> bundled.firstOrNull { it.number == choice.removePrefix("photo:").toIntOrNull() }?.place ?: "One Photo"
        choice.startsWith("cat:") -> categories.firstOrNull { it.id == choice.removePrefix("cat:") }?.title ?: "All Photos"
        else -> "All Photos"
    }

    /** Full-size decode, scaled down for very large uploads. Null if it can't be read. */
    fun decode(c: Context, photo: Photo, maxWidth: Int = 1920): Bitmap? = try {
        when (photo) {
            is Photo.Bundled -> BitmapFactory.decodeResource(c.resources, photo.res)
            is Photo.Local -> decodeFile(photo.file, maxWidth)
            // Not downloadable right now: a bundled photo instead, so the screen is never left empty.
            is Photo.Online -> hdReady(c, photo)?.let { decodeFile(it, maxWidth) } ?: BitmapFactory.decodeResource(c.resources, bundled.random().res)
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

    /** The old "mountains in front of the clock" cut-outs were too rough (they chopped the numbers up), so they're off. */
    @Suppress("UNUSED_PARAMETER")
    fun decodeFront(c: Context, photo: Photo): Bitmap? = null
}
