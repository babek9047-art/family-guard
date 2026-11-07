package com.familyguard.child

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScanActivity : AppCompatActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)
    private lateinit var previewView: PreviewView
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else { toast("Без камеры сканировать нельзя"); finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildContent(): LinearLayout {
        val density = resources.displayMetrics.density
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            addView(TextView(this@QrScanActivity).apply {
                text = "Наведите камеру на QR-код из панели WordPress"
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (12 * density).toInt())
            })
            addView(previewView)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image -> analyze(image) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { toast("Не удалось открыть камеру"); finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (handled.get()) return
            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                data, image.planes[0].rowStride, image.height,
                0, 0, image.width.coerceAtMost(image.planes[0].rowStride), image.height, false
            )
            val result = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            }.getOrNull()
            reader.reset()
            if (result != null && handled.compareAndSet(false, true)) {
                runOnUiThread { finishWith(result.text) }
            }
        } catch (_: Exception) {
            // Кадр без кода — пропускаем.
        } finally {
            image.close()
        }
    }

    private fun finishWith(content: String) {
        val uri = runCatching { Uri.parse(content.trim()) }.getOrNull()
        val site = uri?.getQueryParameter("site")?.trim().orEmpty()
        val code = uri?.getQueryParameter("code")?.trim().orEmpty()
        if (uri?.scheme == "familyguard" && site.isNotBlank() && code.isNotBlank()) {
            setResult(Activity.RESULT_OK, Intent()
                .putExtra(EXTRA_SITE, site)
                .putExtra(EXTRA_CODE, code))
        } else {
            setResult(Activity.RESULT_CANCELED)
            toast("Это не QR-код Family Guard")
        }
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_CODE = "code"
    }
}
