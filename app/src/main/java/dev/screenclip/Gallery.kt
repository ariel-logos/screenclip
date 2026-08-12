package dev.screenclip

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ALBUM = "ScreenClip"

/** US locale on purpose: a device set to a non-Gregorian calendar would produce junk filenames. */
private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

/** Human-readable destination, e.g. "Pictures/ScreenClip". */
val galleryFolder: String = "${Environment.DIRECTORY_PICTURES}/$ALBUM"

/**
 * Publishes PNG bytes to the shared image collection.
 *
 * No permission is involved: at minSdk 30 an app may always insert its own media,
 * so there is no WRITE_EXTERNAL_STORAGE and nothing to declare in the manifest.
 *
 * Must not run on the main thread.
 */
fun Context.saveToGallery(png: ByteArray): String {
    val resolver = applicationContext.contentResolver
    // VOLUME_EXTERNAL_PRIMARY, not EXTERNAL_CONTENT_URI: the latter is the merged
    // synthetic volume and is documented as not insertable.
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val name = "ScreenClip_${STAMP.format(LocalDateTime.now())}.png"

    val pending = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, galleryFolder)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    // Everything else (size, width, height, dates) is re-derived by the media scanner
    // when the pending flag clears; setting it here is only a chance to be wrong.
    val uri = resolver.insert(collection, pending)
        ?: throw IOException("MediaStore refused an insert into $galleryFolder")

    try {
        val stream = resolver.openOutputStream(uri, "w")
            ?: throw IOException("MediaStore gave no output stream for $uri")
        stream.use { it.write(png) }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    } catch (e: Exception) {
        // A row left pending becomes an invisible .pending- file that the system only
        // reaps after seven days, so drop it before rethrowing.
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
    return "$galleryFolder/$name"
}
