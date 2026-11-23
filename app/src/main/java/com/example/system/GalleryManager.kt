package com.example.system

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class GalleryItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val isRawDng: Boolean,
    val pairedUri: Uri? = null
)

@Immutable
data class ExifMetadata(
    val fileName: String,
    val resolution: String,
    val fileSizeText: String,
    val cameraMakeModel: String,
    val iso: String,
    val shutterSpeed: String,
    val aperture: String,
    val focalLength: String,
    val whiteBalance: String,
    val dateTaken: String,
    val gpsLocation: String,
    val software: String,
    val isRaw: Boolean,
    val lensModel: String = "—",
    val exposureMode: String = "—",
    val exposureBias: String = "—",
    val focusDistance: String = "—",
    val sceneProfile: String = "—"
)

class GalleryManager(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    suspend fun loadCaptures(customSafTreeUri: String?): List<GalleryItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<GalleryItem>()
        queryMediaStoreImages(items)
        queryMediaStoreVideos(items)
        if (!customSafTreeUri.isNullOrBlank()) {
            querySafFolder(Uri.parse(customSafTreeUri), items)
        }

        val sorted = items.distinctBy { it.uri.toString() }
            .sortedByDescending { it.dateAddedSec }

        // Pair RAW DNG with matching JPEG by base name
        val byBaseName = sorted.groupBy { it.displayName.substringBeforeLast('.') }
        sorted.map { item ->
            val siblings = byBaseName[item.displayName.substringBeforeLast('.')] ?: emptyList()
            val paired = if (item.isRawDng) {
                siblings.firstOrNull { !it.isRawDng && !it.isVideo }?.uri
            } else {
                siblings.firstOrNull { it.isRawDng }?.uri
            }
            item.copy(pairedUri = paired)
        }
    }

    private fun queryMediaStoreImages(out: MutableList<GalleryItem>) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%UltraCam%")
        } else null

        runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "IMG_$id"
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val isDng = name.endsWith(".dng", ignoreCase = true) ||
                        mime.contains("dng", ignoreCase = true)
                    out.add(
                        GalleryItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = name,
                            mimeType = mime,
                            dateAddedSec = cursor.getLong(dateCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol),
                            isVideo = false,
                            isRawDng = isDng
                        )
                    )
                }
            }
        }
    }

    private fun queryMediaStoreVideos(out: MutableList<GalleryItem>) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        } else null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%UltraCam%")
        } else null

        runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "VID_$id"
                    val mime = cursor.getString(mimeCol) ?: "video/mp4"
                    out.add(
                        GalleryItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = name,
                            mimeType = mime,
                            dateAddedSec = cursor.getLong(dateCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol),
                            isVideo = true,
                            isRawDng = false
                        )
                    )
                }
            }
        }
    }

    private fun querySafFolder(treeUri: Uri, out: MutableList<GalleryItem>) {
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: childId
                    val mime = cursor.getString(mimeCol) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val isVideo = mime.startsWith("video/")
                    val isImage = mime.startsWith("image/") || name.endsWith(".dng", ignoreCase = true)
                    if (!isVideo && !isImage) continue
                    val isDng = name.endsWith(".dng", ignoreCase = true) || mime.contains("dng", true)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    out.add(
                        GalleryItem(
                            uri = docUri,
                            displayName = name,
                            mimeType = mime,
                            dateAddedSec = cursor.getLong(modCol) / 1000L,
                            sizeBytes = cursor.getLong(sizeCol),
                            width = 0,
                            height = 0,
                            isVideo = isVideo,
                            isRawDng = isDng
                        )
                    )
                }
            }
        }
    }

    suspend fun readExifMetadata(item: GalleryItem): ExifMetadata = withContext(Dispatchers.IO) {
        val sizeStr = if (item.sizeBytes > 1024 * 1024) {
            String.format(java.util.Locale.US, "%.2f MB", item.sizeBytes / (1024f * 1024f))
        } else {
            String.format(java.util.Locale.US, "%.1f KB", item.sizeBytes / 1024f)
        }

        if (item.isVideo) {
            return@withContext ExifMetadata(
                fileName = item.displayName,
                resolution = if (item.width > 0) "${item.width}x${item.height}" else "MP4 Video",
                fileSizeText = sizeStr,
                cameraMakeModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                iso = "—",
                shutterSpeed = "—",
                aperture = "—",
                focalLength = "—",
                whiteBalance = "—",
                dateTaken = "—",
                gpsLocation = "—",
                software = "UltraCam Video Engine",
                isRaw = false
            )
        }

        return@withContext runCatching {
            resolver.openInputStream(item.uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, item.width)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, item.height)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: Build.MANUFACTURER
                val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: Build.MODEL
                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED)
                    ?: "—"
                val exp = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                val shutterStr = if (exp > 0.0) {
                    if (exp >= 1.0) String.format(java.util.Locale.US, "%.2fs", exp)
                    else "1/${(1.0 / exp).toInt().coerceAtLeast(1)}s"
                } else "—"
                val fNum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                val apertureStr = if (fNum > 0.0) String.format(java.util.Locale.US, "f/%.1f", fNum) else "—"
                val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                val focalStr = when {
                    focal > 0.0 && focal35 > 0 ->
                        String.format(java.util.Locale.US, "%.2f mm (%dmm eq)", focal, focal35)
                    focal > 0.0 ->
                        String.format(java.util.Locale.US, "%.2f mm", focal)
                    else -> "—"
                }
                val wb = when (exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)) {
                    ExifInterface.WHITE_BALANCE_AUTO.toInt() -> "Auto"
                    ExifInterface.WHITE_BALANCE_MANUAL.toInt() -> "Manual"
                    else -> "—"
                }
                val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "—"
                val latLong = exif.latLong
                val gpsStr = if (latLong != null) {
                    String.format(java.util.Locale.US, "%.5f, %.5f", latLong[0], latLong[1])
                } else "—"
                val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: "UltraCam"
                val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.takeIf { it.isNotBlank() } ?: "—"
                val expMode = when (exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_MODE, -1)) {
                    0 -> "Auto"
                    1 -> "Manual"
                    2 -> "Auto Bracket"
                    else -> "—"
                }
                val evBiasVal = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)
                val evBiasStr = if (!evBiasVal.isNaN()) {
                    String.format(java.util.Locale.US, "%+.2f EV", evBiasVal)
                } else "—"
                val subjDist = exif.getAttributeDouble(ExifInterface.TAG_SUBJECT_DISTANCE, 0.0)
                val focusStr = if (subjDist > 0.0) {
                    String.format(java.util.Locale.US, "%.2f m", subjDist)
                } else "—"
                val desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.takeIf { it.isNotBlank() } ?: "—"

                ExifMetadata(
                    fileName = item.displayName,
                    resolution = if (w > 0 && h > 0) "${w}x${h}" else "—",
                    fileSizeText = sizeStr,
                    cameraMakeModel = "$make $model".trim(),
                    iso = iso,
                    shutterSpeed = shutterStr,
                    aperture = apertureStr,
                    focalLength = focalStr,
                    whiteBalance = wb,
                    dateTaken = date,
                    gpsLocation = gpsStr,
                    software = software,
                    isRaw = item.isRawDng,
                    lensModel = lensModel,
                    exposureMode = expMode,
                    exposureBias = evBiasStr,
                    focusDistance = focusStr,
                    sceneProfile = desc
                )
            }
        }.getOrNull() ?: ExifMetadata(
            fileName = item.displayName,
            resolution = if (item.width > 0) "${item.width}x${item.height}" else "—",
            fileSizeText = sizeStr,
            cameraMakeModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            iso = "—",
            shutterSpeed = "—",
            aperture = "—",
            focalLength = "—",
            whiteBalance = "—",
            dateTaken = "—",
            gpsLocation = "—",
            software = "UltraCam",
            isRaw = item.isRawDng
        )
    }

    suspend fun deleteItem(item: GalleryItem): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (item.uri.authority?.contains("documents") == true) {
                DocumentsContract.deleteDocument(resolver, item.uri)
            } else {
                resolver.delete(item.uri, null, null) > 0
            }
        }.getOrDefault(false)
    }
}
