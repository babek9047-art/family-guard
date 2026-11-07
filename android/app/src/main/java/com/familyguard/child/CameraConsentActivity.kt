package com.familyguard.child

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraConsentActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private var imageCapture: ImageCapture? = null
    private var commandId: Long = -1

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            toast("Без разрешения камеры снимок невозможен")
            captureButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commandId = intent.getLongExtra(EXTRA_COMMAND_ID, -1)
        if (commandId < 0 || !AppPrefs(this).isPaired) {
            finish()
            return
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContentView(buildContent())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildContent(): LinearLayout {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        captureButton = Button(this).apply {
            text = "Сделать и отправить снимок"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { captureAndUpload() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@CameraConsentActivity).apply {
                text = "Запрос снимка от родителя"
                textSize = 24f
            })
            addView(TextView(this@CameraConsentActivity).apply {
                text = "Камера видна на экране. Снимок будет отправлен только после нажатия кнопки ниже."
                textSize = 16f
                setPadding(0, 8, 0, 14)
            })
            addView(previewView)
            addView(captureButton)
            addView(Button(this@CameraConsentActivity).apply {
                text = "Отклонить запрос"
                isAllCaps = false
                gravity = Gravity.CENTER
                setOnClickListener { rejectRequest() }
            })
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageCapture
                )
                captureButton.isEnabled = true
            }.onFailure { toast("Не удалось открыть камеру") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndUpload() {
        val capture = imageCapture ?: return
        captureButton.isEnabled = false
        captureButton.text = "Отправка…"
        val output = File(cacheDir, "family_guard_${commandId}_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(output).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    runCatching {
                        val api = ApiClient(this@CameraConsentActivity)
                        api.uploadSnapshot(commandId, output)
                        api.completeCommand(commandId, "completed", "Снимок отправлен с подтверждением пользователя")
                    }.onSuccess {
                        output.delete()
                        runOnUiThread {
                            cancelRequestNotification()
                            toast("Снимок отправлен")
                            finish()
                        }
                    }.onFailure { error -> runOnUiThread {
                        captureButton.isEnabled = true
                        captureButton.text = "Повторить отправку"
                        toast(error.message ?: "Ошибка отправки")
                    } }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        captureButton.isEnabled = true
                        captureButton.text = "Повторить"
                        toast("Не удалось сделать снимок")
                    }
                }
            }
        )
    }

    private fun rejectRequest() {
        captureButton.isEnabled = false
        cameraExecutor.execute {
            runCatching { ApiClient(this).completeCommand(commandId, "declined", "Пользователь отклонил запрос") }
            runOnUiThread {
                cancelRequestNotification()
                finish()
            }
        }
    }

    private fun cancelRequestNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(MonitoringService.CAMERA_NOTIFICATION_BASE + commandId.toInt())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object { const val EXTRA_COMMAND_ID = "command_id" }
}
