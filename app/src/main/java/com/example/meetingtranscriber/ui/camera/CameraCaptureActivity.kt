package com.example.meetingtranscriber.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.databinding.ActivityCameraCaptureBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_URI = "photo_uri"
        private const val TAG = "CameraCapture"
    }

    private lateinit var binding: ActivityCameraCaptureBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashEnabled: Boolean = false
    private var capturedImageFile: File? = null

    private val mainScope = CoroutineScope(Dispatchers.Main)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnRetake.setOnClickListener { retakePhoto() }
        binding.btnConfirm.setOnClickListener { confirmPhoto() }

        // 点击预览区域对焦
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                focusOnPoint(event.x, event.y)
            }
            true
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "相机绑定失败: ${e.message}", e)
                Toast.makeText(this, "无法启动相机，请检查权限或设备相机状态", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusOnPoint(x: Float, y: Float) {
        val camera = this.camera ?: return
        val factory = binding.viewFinder.meteringPointFactory
        val point: MeteringPoint = factory.createPoint(x, y, 0.25f)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        runCatching { camera.cameraControl.startFocusAndMetering(action) }
    }

    private fun takePhoto() {
        val imageCapture = this.imageCapture ?: return

        binding.btnCapture.isEnabled = false

        val photoFile = File(
            cacheDir, "camera",
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        ).also {
            it.parentFile?.mkdirs()
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    android.util.Log.i(TAG, "拍照成功 size=${photoFile.length()}")
                    runOnUiThread {
                        showPreview(photoFile)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    android.util.Log.e(TAG, "拍照失败: ${exception.message}", exception)
                    runOnUiThread {
                        binding.btnCapture.isEnabled = true
                        Toast.makeText(
                            this@CameraCaptureActivity,
                            "拍照失败，请重试",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun showPreview(photoFile: File) {
        capturedImageFile = photoFile

        binding.layoutCapture.visibility = View.GONE
        binding.layoutConfirm.visibility = View.VISIBLE
        binding.btnFlash.visibility = View.GONE

        // 先隐藏预览流，展示拍到的照片
        binding.viewFinder.visibility = View.GONE
        binding.ivPreview.visibility = View.VISIBLE
        binding.ivPreview.setImageURI(Uri.fromFile(photoFile))
    }

    private fun retakePhoto() {
        capturedImageFile?.delete()
        capturedImageFile = null

        binding.layoutCapture.visibility = View.VISIBLE
        binding.layoutConfirm.visibility = View.GONE
        binding.btnFlash.visibility = View.VISIBLE
        binding.viewFinder.visibility = View.VISIBLE
        binding.ivPreview.visibility = View.GONE
        binding.btnCapture.isEnabled = true
        binding.ivPreview.setImageDrawable(null)
    }

    private fun confirmPhoto() {
        val sourceFile = capturedImageFile ?: run {
            Toast.makeText(this, "照片数据丢失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnConfirm.isEnabled = false
        binding.btnRetake.isEnabled = false

        mainScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    saveToMediaStore(sourceFile)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CameraCaptureActivity, "已保存到相册", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.util.Log.e(TAG, "保存失败: ${e.message}", e)
                    binding.btnConfirm.isEnabled = true
                    binding.btnRetake.isEnabled = true
                    Toast.makeText(this@CameraCaptureActivity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveToMediaStore(sourceFile: File) {
        val resolver = contentResolver
        val name = "MT_${sourceFile.name}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeetingTranscriber")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert 返回 null")

        resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out, bufferSize = 8192) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        // 清理缓存文件
        sourceFile.delete()

        android.util.Log.i(TAG, "照片已存入相册: $uri")
    }

    private fun toggleFlash() {
        val imageCapture = this.imageCapture ?: return
        flashEnabled = !flashEnabled
        imageCapture.flashMode = if (flashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        binding.btnFlash.setImageResource(
            if (flashEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
