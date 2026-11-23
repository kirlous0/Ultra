package com.example.system

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedMediaResult(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long
)

class StorageManager(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun getAvailableStorageBytes(): Long {
        return runCatching {
            val path = Environment.getExternalStorageDirectory() ?: context.filesDir
            val stat = StatFs(path.path)
            stat.availableBytes
        }.getOrDefault(0L)
    }

    fun isCriticallyLowStorage(): Boolean {
        val availMb = getAvailableStorageBytes() / (1024L * 1024L)
        return availMb in 1..150
    }

    fun estimateVideoSecondsRemaining(bitrateBps: Int): Long {
        val bytesPerSec = (bitrateBps / 8L).coerceAtLeast(125_000L)
        val usableBytes = (getAvailableStorageBytes() - 100L * 1024L * 1024L).coerceAtLeast(0L)
        return usableBytes / bytesPerSec
    }

    fun buildUniqueFilename(prefix: String, modeTag: String, extension: String): String {
        val cleanPrefix = prefix.replace(Regex("[^A-Za-z0-9_-]"), "").ifBlank { "UCAM" }
        val cleanTag = modeTag.replace(Regex("[^A-Za-z0-9_-]"), "").ifBlank { "PHOTO" }
        val ts = timestampFormatter.format(Date())
        val uniqueSuffix = (System.nanoTime() % 1000L).toString().padStart(3, '0')
        return "${cleanPrefix}_${ts}_${cleanTag}_${uniqueSuffix}.$extension"
    }

    suspend fun savePhotoBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        settings: UltraCamSettings,
        captureLocation: Location? = null
    ): SavedMediaResult? = withContext(Dispatchers.IO) {
        val safUri = settings.customSafTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val savedUri = if (safUri != null) {
            saveToSafTree(safUri, displayName, mimeType, bytes)
        } else {
            saveImageToMediaStore(displayName, mimeType, bytes)
        } ?: return@withContext null

        if (mimeType == "image/jpeg" && settings.gpsExifEnabled && captureLocation != null) {
            applyGpsExif(savedUri, captureLocation)
        }

        SavedMediaResult(
            uri = savedUri,
            displayName = displayName,
            mimeType = mimeType,
            byteSize = bytes.size.toLong()
        )
    }

    suspend fun createStreamTargetForDng(
        displayName: String,
        settings: UltraCamSettings
    ): Pair<Uri, OutputStream>? = withContext(Dispatchers.IO) {
        val mimeType = "image/x-adobe-dng"
        val safUri = settings.customSafTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uri = if (safUri != null) {
            createSafDocument(safUri, displayName, mimeType)
        } else {
            createImageMediaStoreEntry(displayName, mimeType)
        } ?: return@withContext null

        val out = runCatching { resolver.openOutputStream(uri) }.getOrNull() ?: return@withContext null
        uri to out
    }

    fun finalizeMediaStoreEntry(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, values, null, null)
            }
        }
    }

    suspend fun createTempVideoFile(displayName: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "video_staging").apply { mkdirs() }
        File(dir, displayName)
    }

    suspend fun publishVideoFile(
        tempFile: File,
        displayName: String,
        settings: UltraCamSettings
    ): SavedMediaResult? = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() == 0L) return@withContext null
        val mimeType = "video/mp4"
        val safUri = settings.customSafTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }

        val targetUri = if (safUri != null) {
            createSafDocument(safUri, displayName, mimeType)
        } else {
            createVideoMediaStoreEntry(displayName, mimeType)
        } ?: return@withContext null

        val copied = runCatching {
            resolver.openOutputStream(targetUri)?.use { out ->
                tempFile.inputStream().use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            }
            finalizeMediaStoreEntry(targetUri)
            true
        }.getOrDefault(false)

        val size = tempFile.length()
        tempFile.delete()

        if (!copied) return@withContext null
        SavedMediaResult(targetUri, displayName, mimeType, size)
    }

    suspend fun saveJsonDocument(
        displayName: String,
        jsonText: String,
        settings: UltraCamSettings
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = jsonText.toByteArray(Charsets.UTF_8)
        val safUri = settings.customSafTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (safUri != null) {
            return@withContext saveToSafTree(safUri, displayName, "application/json", bytes)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/UltraCam")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val uri = resolver.insert(collection, values) ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            finalizeMediaStoreEntry(uri)
            uri
        }.getOrNull()
    }

    fun getLastKnownCoarseLocation(): Location? {
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!coarseGranted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()
    }

    fun getStudioRecipeFile(mediaUri: Uri): File {
        val dir = File(context.filesDir, "studio_recipes").apply { mkdirs() }
        val key = mediaUri.toString().hashCode().toUInt().toString(16)
        return File(dir, "recipe_$key.json")
    }

    private fun saveImageToMediaStore(
        displayName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri? {
        val uri = createImageMediaStoreEntry(displayName, mimeType) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            finalizeMediaStoreEntry(uri)
            uri
        }.getOrNull()
    }

    private fun createImageMediaStoreEntry(displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/UltraCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return resolver.insert(collection, values)
    }

    private fun createVideoMediaStoreEntry(displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/UltraCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return resolver.insert(collection, values)
    }

    private fun createSafDocument(treeUri: Uri, displayName: String, mimeType: String): Uri? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            DocumentsContract.createDocument(resolver, parentDocUri, mimeType, displayName)
        }.getOrNull()
    }

    private fun saveToSafTree(
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri? {
        val docUri = createSafDocument(treeUri, displayName, mimeType) ?: return null
        return runCatching {
            resolver.openOutputStream(docUri)?.use { it.write(bytes) }
            docUri
        }.getOrNull()
    }

    private fun applyGpsExif(uri: Uri, location: Location) {
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setGpsInfo(location)
                exif.saveAttributes()
            }
        }
    }
}
