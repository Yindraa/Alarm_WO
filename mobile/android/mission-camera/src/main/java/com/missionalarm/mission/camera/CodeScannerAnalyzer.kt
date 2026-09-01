package com.missionalarm.mission.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ExperimentalGetImage
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class CodeScannerAnalyzer(
  private val callbackExecutor: Executor,
  private val onCode: (String) -> Unit,
  private val onMultipleCodes: () -> Unit,
  private val onUnreadable: () -> Unit,
  private val stableGate: StableCodeGate = StableCodeGate(requiredDetections = 2),
  private val scanner: BarcodeScanner = BarcodeScanning.getClient(
    BarcodeScannerOptions.Builder()
      .setBarcodeFormats(
        Barcode.FORMAT_QR_CODE,
        Barcode.FORMAT_EAN_13,
        Barcode.FORMAT_EAN_8,
        Barcode.FORMAT_UPC_A,
        Barcode.FORMAT_UPC_E,
        Barcode.FORMAT_CODE_128,
        Barcode.FORMAT_CODE_39,
      )
      .build(),
  ),
) : ImageAnalysis.Analyzer, Closeable {
  private val processing = AtomicBoolean(false)
  private val delivered = AtomicBoolean(false)

  @ExperimentalGetImage
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
        val supported = barcodes.mapNotNull { barcode ->
          val value = barcode.rawValue?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
          val format = formatName(barcode.format) ?: return@mapNotNull null
          DecodedCode(value, format)
        }
        when (val outcome = CodeFramePolicy.select(supported)) {
          CodeFrameOutcome.None -> Unit
          CodeFrameOutcome.Multiple -> {
            stableGate.reset()
            onMultipleCodes()
          }
          is CodeFrameOutcome.Single -> stableGate.observe(outcome.code)?.let { evidence ->
            if (delivered.compareAndSet(false, true)) onCode(evidence.format)
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

  private fun formatName(format: Int): String? = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR_CODE"
    Barcode.FORMAT_EAN_13 -> "EAN_13"
    Barcode.FORMAT_EAN_8 -> "EAN_8"
    Barcode.FORMAT_UPC_A -> "UPC_A"
    Barcode.FORMAT_UPC_E -> "UPC_E"
    Barcode.FORMAT_CODE_128 -> "CODE_128"
    Barcode.FORMAT_CODE_39 -> "CODE_39"
    else -> null
  }
}

internal data class DecodedCode(val payload: String, val format: String)

internal sealed interface CodeFrameOutcome {
  data object None : CodeFrameOutcome
  data class Single(val code: DecodedCode) : CodeFrameOutcome
  data object Multiple : CodeFrameOutcome
}

internal object CodeFramePolicy {
  fun select(codes: List<DecodedCode>): CodeFrameOutcome {
    val distinct = codes.distinctBy { it.payload to it.format }.take(2)
    return when (distinct.size) {
      0 -> CodeFrameOutcome.None
      1 -> CodeFrameOutcome.Single(distinct.single())
      else -> CodeFrameOutcome.Multiple
    }
  }
}

/** Keeps raw values in memory only long enough to reject one-frame false detections. */
internal class StableCodeGate(private val requiredDetections: Int) {
  init {
    require(requiredDetections >= 2)
  }

  private var candidate: DecodedCode? = null
  private var count = 0

  fun observe(code: DecodedCode): DecodedCode? {
    if (candidate == code) {
      count += 1
    } else {
      candidate = code
      count = 1
    }
    return code.takeIf { count >= requiredDetections }.also {
      if (it != null) reset()
    }
  }

  fun reset() {
    candidate = null
    count = 0
  }
}
