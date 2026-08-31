package com.missionalarm.mission.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class QrCodeAnalyzer(
  private val callbackExecutor: Executor,
  private val onPayload: (String) -> Unit,
  private val onMultipleCodes: () -> Unit,
  private val onUnreadable: () -> Unit,
  private val scanner: BarcodeScanner = BarcodeScanning.getClient(
    BarcodeScannerOptions.Builder()
      .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
      .build(),
  ),
) : ImageAnalysis.Analyzer, Closeable {
  private val processing = AtomicBoolean(false)
  private val delivered = AtomicBoolean(false)

  override fun analyze(imageProxy: ImageProxy) {
    if (delivered.get() || !processing.compareAndSet(false, true)) {
      imageProxy.close()
      return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
      processing.set(false)
      imageProxy.close()
      return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
      .addOnSuccessListener(callbackExecutor) { barcodes ->
        val outcome = QrDecodePolicy.select(barcodes
          .asSequence()
          .filter { it.format == Barcode.FORMAT_QR_CODE }
          .mapNotNull { it.rawValue }
          .toList())
        when (outcome) {
          QrDecodeOutcome.None -> Unit
          QrDecodeOutcome.Multiple -> onMultipleCodes()
          is QrDecodeOutcome.Single -> if (delivered.compareAndSet(false, true)) {
            onPayload(outcome.payload)
          }
        }
      }
      .addOnFailureListener(callbackExecutor) { onUnreadable() }
      .addOnCompleteListener(callbackExecutor) {
        processing.set(false)
        imageProxy.close()
      }
  }

  override fun close() {
    delivered.set(true)
    scanner.close()
  }
}

internal sealed interface QrDecodeOutcome {
  data object None : QrDecodeOutcome
  data class Single(val payload: String) : QrDecodeOutcome
  data object Multiple : QrDecodeOutcome
}

internal object QrDecodePolicy {
  fun select(rawValues: List<String>): QrDecodeOutcome {
    val distinct = rawValues.asSequence().filter(String::isNotEmpty).distinct().take(2).toList()
    return when (distinct.size) {
      0 -> QrDecodeOutcome.None
      1 -> QrDecodeOutcome.Single(distinct.single())
      else -> QrDecodeOutcome.Multiple
    }
  }
}
