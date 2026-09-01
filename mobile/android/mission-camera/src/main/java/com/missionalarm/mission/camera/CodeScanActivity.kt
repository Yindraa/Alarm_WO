package com.missionalarm.mission.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Internal camera surface that returns format-only scan evidence to the native alarm host. */
class CodeScanActivity : ComponentActivity() {
  private lateinit var previewView: PreviewView
  private lateinit var statusText: TextView
  private lateinit var permissionPanel: LinearLayout
  private lateinit var permissionButton: Button
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private var cameraProvider: ProcessCameraProvider? = null
  private var analyzer: CodeScannerAnalyzer? = null
  private var delivered = false

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      permissionPanel.visibility = View.GONE
      startCamera()
    } else {
      showPermissionRecovery()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (sessionToken(intent) == null) {
      finish()
      return
    }
    buildContent()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      startCamera()
    } else {
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  override fun onDestroy() {
    cameraProvider?.unbindAll()
    analyzer?.close()
    analyzer = null
    cameraExecutor.shutdown()
    super.onDestroy()
  }

  private fun startCamera() {
    statusText.setText(R.string.code_scan_searching)
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      runCatching {
        val provider = future.get()
        cameraProvider = provider
        val preview = Preview.Builder().build().also {
          it.surfaceProvider = previewView.surfaceProvider
        }
        val codeAnalyzer = CodeScannerAnalyzer(
          callbackExecutor = ContextCompat.getMainExecutor(this),
          onCode = ::deliverEvidence,
          onMultipleCodes = { statusText.setText(R.string.code_scan_multiple) },
          onUnreadable = { statusText.setText(R.string.code_scan_unreadable) },
        )
        analyzer = codeAnalyzer
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(cameraExecutor, codeAnalyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
      }.onFailure { showTerminalError() }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun deliverEvidence(format: String) {
    if (delivered || isFinishing || isDestroyed) return
    delivered = true
    val token = sessionToken(intent) ?: return showTerminalError()
    statusText.setText(R.string.code_scan_found)
    setResult(RESULT_OK, Intent().apply {
      putExtra(EXTRA_SESSION_TOKEN, token)
      putExtra(EXTRA_CODE_FORMAT, format)
    })
    finish()
  }

  private fun showTerminalError() {
    statusText.post {
      if (isFinishing || isDestroyed) return@post
      statusText.setText(R.string.code_scan_error)
      permissionButton.setText(R.string.code_scan_close)
      permissionButton.setOnClickListener { finish() }
      permissionPanel.visibility = View.VISIBLE
    }
  }

  private fun showPermissionRecovery() {
    permissionPanel.visibility = View.VISIBLE
    permissionButton.setText(R.string.code_camera_permission_action)
    permissionButton.setOnClickListener {
      if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
      } else {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.fromParts("package", packageName, null)
        })
      }
    }
  }

  private fun buildContent() {
    previewView = PreviewView(this).apply {
      id = R.id.code_scan_preview
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    statusText = textView(14, Color.WHITE).apply {
      id = R.id.code_scan_status
      gravity = Gravity.CENTER
      setText(R.string.code_scan_searching)
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    permissionButton = Button(this).apply {
      minHeight = dp(52)
      setTextColor(Color.WHITE)
      background = rounded(Color.rgb(82, 109, 130), 16)
    }
    permissionPanel = LinearLayout(this).apply {
      id = R.id.code_scan_permission_panel
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(24), dp(24), dp(24), dp(24))
      background = rounded(Color.argb(235, 16, 24, 32), 20)
      visibility = View.GONE
      addView(textView(20, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.code_camera_permission_title)
      })
      addView(textView(14, Color.rgb(205, 216, 224)).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(16))
        setText(R.string.code_camera_permission_body)
      })
      addView(permissionButton, LinearLayout.LayoutParams(-1, dp(52)))
    }
    val overlay = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(24), dp(28), dp(24), dp(28))
      addView(textView(11, Color.rgb(255, 181, 71)).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.14f
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.code_scan_eyebrow)
      })
      addView(textView(28, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(4))
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.code_scan_title)
      })
      addView(textView(15, Color.rgb(224, 231, 236)).apply {
        gravity = Gravity.CENTER
        setText(R.string.code_scan_instruction)
      })
      addView(View(this@CodeScanActivity).apply {
        contentDescription = getString(R.string.code_scan_frame_description)
        background = GradientDrawable().apply {
          setColor(Color.TRANSPARENT)
          cornerRadius = dp(28).toFloat()
          setStroke(dp(3), Color.rgb(255, 181, 71))
        }
      }, LinearLayout.LayoutParams(-1, 0, 1f).apply {
        setMargins(dp(16), dp(30), dp(16), dp(30))
      })
      addView(statusText, LinearLayout.LayoutParams(-1, dp(48)))
      addView(textView(12, Color.rgb(205, 216, 224)).apply {
        gravity = Gravity.CENTER
        setText(R.string.code_scan_privacy)
      })
      addView(Button(this@CodeScanActivity).apply {
        minHeight = dp(48)
        setText(R.string.code_scan_close)
        setTextColor(Color.WHITE)
        background = rounded(Color.argb(150, 30, 43, 54), 16)
        setOnClickListener { finish() }
      }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
    }
    setContentView(FrameLayout(this).apply {
      setBackgroundColor(Color.rgb(16, 24, 32))
      addView(previewView, FrameLayout.LayoutParams(-1, -1))
      addView(View(this@CodeScanActivity).apply {
        setBackgroundColor(Color.argb(80, 0, 0, 0))
      }, FrameLayout.LayoutParams(-1, -1))
      addView(overlay, FrameLayout.LayoutParams(-1, -1))
      addView(permissionPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
        leftMargin = dp(28)
        rightMargin = dp(28)
      })
    })
  }

  private fun textView(sizeSp: Int, color: Int) = TextView(this).apply {
    textSize = sizeSp.toFloat()
    setTextColor(color)
  }

  private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  companion object {
    private const val EXTRA_SESSION_TOKEN = "scanSessionToken"
    private const val EXTRA_CODE_FORMAT = "scanCodeFormat"

    fun intent(context: Context, sessionToken: String): Intent {
      UUID.fromString(sessionToken)
      return Intent(context, CodeScanActivity::class.java).apply {
        putExtra(EXTRA_SESSION_TOKEN, sessionToken)
      }
    }

    fun sessionToken(intent: Intent?): String? = intent?.getStringExtra(EXTRA_SESSION_TOKEN)
      ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }

    fun codeFormat(intent: Intent?): String? = intent?.getStringExtra(EXTRA_CODE_FORMAT)
      ?.takeIf { it in SUPPORTED_FORMATS }

    private val SUPPORTED_FORMATS = setOf(
      "QR_CODE", "EAN_13", "EAN_8", "UPC_A", "UPC_E", "CODE_128", "CODE_39",
    )
  }
}
