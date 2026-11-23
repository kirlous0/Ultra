package com.example.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class QrScanResult(
    val rawValue: String,
    val formatLabel: String,
    val isUrl: Boolean
)

class QrScannerEngine {

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_CODE_128
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    suspend fun scanBitmap(bitmap: Bitmap): QrScanResult? = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val hit = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (hit != null && cont.isActive) {
                    cont.resume(
                        QrScanResult(
                            rawValue = hit.rawValue ?: "",
                            formatLabel = if (hit.format == Barcode.FORMAT_QR_CODE) "QR" else "BARCODE",
                            isUrl = hit.valueType == Barcode.TYPE_URL
                        )
                    )
                } else if (cont.isActive) {
                    cont.resume(null)
                }
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
    }

    fun close() {
        runCatching { scanner.close() }
    }
}
