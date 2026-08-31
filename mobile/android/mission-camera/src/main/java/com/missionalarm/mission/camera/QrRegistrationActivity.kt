package com.missionalarm.mission.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import com.missionalarm.core.data.QrDigestService
import com.missionalarm.core.data.QrRegistrationRepository
import com.missionalarm.core.data.RegisterQrReferenceCommand
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.Revision
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrRegistrationActivity : ComponentActivity() {
  private lateinit var previewView: PreviewView
  private lateinit var statusText: TextView
  private lateinit var permissionPanel: LinearLayout
  private lateinit var permissionButton: Button
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private var cameraProvider: ProcessCameraProvider? = null
  private var analyzer: QrCodeAnalyzer? = null
  private var registering = false

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
    if (readRequest() == null) {
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
    statusText.setText(R.string.qr_registration_searching)
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      runCatching {
        val provider = future.get()
        cameraProvider = provider
        val preview = Preview.Builder().build().also {
          it.surfaceProvider = previewView.surfaceProvider
        }
        val qrAnalyzer = QrCodeAnalyzer(
          // ML Kit can finish after CameraX has been unbound. Keep its completion
          // callbacks on the lifecycle-safe main executor instead of the camera
          // executor, which is shut down when this Activity is destroyed.
          callbackExecutor = ContextCompat.getMainExecutor(this),
          onPayload = ::registerPayload,
          onMultipleCodes = {
            statusText.post { statusText.setText(R.string.qr_registration_multiple) }
          },
          onUnreadable = {
            statusText.post { statusText.setText(R.string.qr_registration_unreadable) }
          },
        )
        analyzer = qrAnalyzer
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(cameraExecutor, qrAnalyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(
          this,
          CameraSelector.DEFAULT_BACK_CAMERA,
          preview,
          analysis,
        )
      }.onFailure { showTerminalError() }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun registerPayload(payload: String) {
    if (registering) return
    registering = true
    statusText.setText(R.string.qr_registration_saving)
    val request = readRequest() ?: return showTerminalError()
    cameraExecutor.execute {
      runCatching {
        val reference = QrDigestService(AndroidQrHmacProvider())
          .createReference(payload, KEY_ALIAS)
        val db = MissionAlarmDatabaseFactory.persistent(applicationContext)
        try {
          QrRegistrationRepository(db) { System.currentTimeMillis() }.register(
            RegisterQrReferenceCommand(
              commandId = CommandId.parse(request.requestId),
              alarmId = AlarmId.parse(request.alarmId),
              expectedRevision = Revision.of(request.expectedRevision),
              reference = reference,
            ),
          )
        } finally {
          db.close()
        }
      }.onSuccess { ack ->
        runOnUiThread {
          if (isDestroyed) return@runOnUiThread
          setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_RESULT_REVISION, ack.revision)
          })
          Toast.makeText(this, R.string.qr_registration_success, Toast.LENGTH_SHORT).show()
          finish()
        }
      }.onFailure { showTerminalError() }
    }
  }

  private fun showTerminalError() {
    statusText.post {
      statusText.setText(R.string.qr_registration_error)
      permissionButton.setText(R.string.qr_close)
      permissionButton.setOnClickListener { finish() }
      permissionPanel.visibility = View.VISIBLE
    }
  }

  private fun showPermissionRecovery() {
    permissionPanel.visibility = View.VISIBLE
    permissionButton.setText(R.string.qr_camera_permission_action)
    permissionButton.setOnClickListener {
      if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
      } else {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = android.net.Uri.fromParts("package", packageName, null)
        })
      }
    }
  }

  private fun buildContent() {
    previewView = PreviewView(this).apply {
      id = R.id.qr_registration_preview
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    statusText = textView(14, Color.WHITE).apply {
      id = R.id.qr_registration_status
      gravity = Gravity.CENTER
      setText(R.string.qr_registration_searching)
    }
    permissionButton = Button(this).apply {
      minHeight = dp(52)
      setTextColor(Color.WHITE)
      background = rounded(Color.rgb(82, 109, 130), 16)
      setOnClickListener { permissionLauncher.launch(Manifest.permission.CAMERA) }
    }
    permissionPanel = LinearLayout(this).apply {
      id = R.id.qr_registration_permission_panel
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(24), dp(24), dp(24), dp(24))
      background = rounded(Color.argb(235, 16, 24, 32), 20)
      visibility = View.GONE
      addView(textView(20, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.qr_camera_permission_title)
      })
      addView(textView(14, Color.rgb(205, 216, 224)).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(16))
        setText(R.string.qr_camera_permission_body)
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
        setText(R.string.qr_registration_eyebrow)
      })
      addView(textView(28, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(4))
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.qr_registration_title)
      })
      addView(textView(15, Color.rgb(224, 231, 236)).apply {
        gravity = Gravity.CENTER
        setText(R.string.qr_registration_instruction)
      })
      addView(View(this@QrRegistrationActivity).apply {
        contentDescription = getString(R.string.qr_registration_instruction)
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
        setText(R.string.qr_registration_privacy)
      })
      addView(Button(this@QrRegistrationActivity).apply {
        minHeight = dp(48)
        setText(R.string.qr_close)
        setTextColor(Color.WHITE)
        background = rounded(Color.argb(150, 30, 43, 54), 16)
        setOnClickListener { finish() }
      }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
    }
    setContentView(FrameLayout(this).apply {
      setBackgroundColor(Color.rgb(16, 24, 32))
      addView(previewView, FrameLayout.LayoutParams(-1, -1))
      addView(View(this@QrRegistrationActivity).apply {
        setBackgroundColor(Color.argb(80, 0, 0, 0))
      }, FrameLayout.LayoutParams(-1, -1))
      addView(overlay, FrameLayout.LayoutParams(-1, -1))
      addView(permissionPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
        leftMargin = dp(28)
        rightMargin = dp(28)
      })
    })
  }

  private fun readRequest(): RegistrationRequest? {
    val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return null
    val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return null
    val revision = intent.getIntExtra(EXTRA_EXPECTED_REVISION, 0)
    return runCatching {
      UUID.fromString(requestId)
      AlarmId.parse(alarmId)
      require(revision >= 1)
      RegistrationRequest(requestId, alarmId, revision)
    }.getOrNull()
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

  private data class RegistrationRequest(
    val requestId: String,
    val alarmId: String,
    val expectedRevision: Int,
  )

  companion object {
    private const val EXTRA_REQUEST_ID = "requestId"
    private const val EXTRA_ALARM_ID = "alarmId"
    private const val EXTRA_EXPECTED_REVISION = "expectedRevision"
    private const val EXTRA_RESULT_REVISION = "resultRevision"
    private const val KEY_ALIAS = "mission_alarm_qr_hmac_v1"

    fun intent(
      context: Context,
      requestId: String,
      alarmId: String,
      expectedRevision: Int,
    ): Intent = Intent(context, QrRegistrationActivity::class.java).apply {
      putExtra(EXTRA_REQUEST_ID, requestId)
      putExtra(EXTRA_ALARM_ID, alarmId)
      putExtra(EXTRA_EXPECTED_REVISION, expectedRevision)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }
}
