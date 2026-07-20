package com.example.meetingtranscriber.ui.home

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 使用 Camera2 API 直接拍照，绕过有 bug 的系统相机应用。
 *
 * 针对 USB 摄像头（如 SYP-13M）的实际情况：
 * - 不支持 JPEG 格式 → 使用 YUV_420_888 捕获，软件编码为 JPEG
 * - Legacy HAL v3.3 → 保守设置
 */
class Camera2CaptureHelper(private val context: Context) {

    companion object {
        private const val TAG = "Camera2Capture"
        private const val CAMERA_OPEN_TIMEOUT_MS = 4000L
        private const val JPEG_QUALITY = 85
        /** 应用相册目录路径 */
        const val ALBUM_PATH = "Pictures/MeetingTranscriber"
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    /** Semaphore(0)：初始无 permit，onOpened release 后 tryAcquire 才成功，超时保护才有效 */
    private val cameraOpenLock = Semaphore(0)
    /** 超时或异常后置位，防止 onOpened 迟到触发双重拍照 */
    @Volatile private var cancelled = false

    fun capture(
        onSuccess: (android.net.Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        startBackgroundThread()

        val cameraId = findBackCameraId()
        if (cameraId == null) {
            onError("未找到后置摄像头")
            stopBackgroundThread()
            return
        }

        Log.i(TAG, "打开摄像头 ID: $cameraId")

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (cancelled) {
                        device.close()
                        return
                    }
                    Log.i(TAG, "摄像头已打开")
                    cameraDevice = device
                    cameraOpenLock.release()
                    createCaptureSession(onSuccess, onError)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "摄像头断开连接")
                    closeCamera()
                    stopBackgroundThread()
                    cameraOpenLock.release()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "摄像头打开错误: $error")
                    closeCamera()
                    stopBackgroundThread()
                    cameraOpenLock.release()
                    onError("打开摄像头失败 (错误码 $error)")
                }
            }, backgroundHandler)

            if (!cameraOpenLock.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancelled = true
                onError("打开摄像头超时")
                stopBackgroundThread()
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException: ${e.message}")
            onError("无法访问摄像头: ${e.message}")
            stopBackgroundThread()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            onError("没有摄像头权限")
            stopBackgroundThread()
        }
    }

    private fun findBackCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK ||
                facing == CameraCharacteristics.LENS_FACING_EXTERNAL
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "获取摄像头列表失败: ${e.message}")
            null
        }
    }

    private fun createCaptureSession(
        onSuccess: (android.net.Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val device = cameraDevice ?: run {
            onError("摄像头未就绪")
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(device.id)
        val streamConfigMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )

        val yuvSizes = streamConfigMap?.getOutputSizes(ImageFormat.YUV_420_888)
        val captureSize = if (yuvSizes != null && yuvSizes.isNotEmpty()) {
            val sorted = yuvSizes.sortedByDescending { it.width * it.height }
            val preferred = sorted.firstOrNull { it.width <= 1920 } ?: sorted.first()
            Log.i(TAG, "YUV 可用分辨率: ${sorted.take(3).joinToString { "${it.width}x${it.height}" }}")
            preferred
        } else {
            android.util.Size(1920, 1080)
        }

        Log.i(TAG, "选择捕获分辨率: ${captureSize.width}x${captureSize.height}")

        val reader = ImageReader.newInstance(
            captureSize.width, captureSize.height,
            ImageFormat.YUV_420_888, 2
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: run {
                Log.w(TAG, "acquireLatestImage 返回 null，释放资源")
                closeCamera()
                stopBackgroundThread()
                return@setOnImageAvailableListener
            }
            try {
                Log.i(TAG, "YUV 图像: ${image.width}x${image.height}")
                val jpegBytes = yuvToJpeg(image)
                Log.i(TAG, "YUV→JPEG 编码完成: ${jpegBytes.size} bytes")
                saveToMediaStore(jpegBytes, onSuccess, onError)
            } catch (e: Exception) {
                Log.e(TAG, "YUV→JPEG 编码失败: ${e.message}", e)
                onError("图像处理失败: ${e.message}")
            } finally {
                image.close()
            }
            closeCamera()
            stopBackgroundThread()
        }, backgroundHandler)

        try {
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "CaptureSession 配置成功")
                        captureSession = session

                        try {
                            val requestBuilder = device.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE
                            )
                            requestBuilder.addTarget(reader.surface)
                            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                            Log.i(TAG, "发送拍照请求...")
                            session.capture(requestBuilder.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: android.hardware.camera2.TotalCaptureResult
                                    ) {
                                        Log.i(TAG, "拍照完成，等待图像回调...")
                                    }

                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: android.hardware.camera2.CaptureFailure
                                    ) {
                                        Log.e(TAG, "拍照失败 reason=${failure.reason}")
                                        onError("拍照失败")
                                        closeCamera()
                                        stopBackgroundThread()
                                    }
                                }, backgroundHandler)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "创建拍照请求失败: ${e.message}")
                            onError("拍照失败: ${e.message}")
                            closeCamera()
                            stopBackgroundThread()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CaptureSession 配置失败")
                        onError("相机配置失败")
                        closeCamera()
                        stopBackgroundThread()
                    }
                }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "创建 CaptureSession 失败: ${e.message}")
            onError("相机配置失败: ${e.message}")
            closeCamera()
            stopBackgroundThread()
        }
    }

    private fun yuvToJpeg(image: android.media.Image): ByteArray {
        val width = image.width
        val height = image.height

        val yuvBytes = ByteArray(width * height * 3 / 2)
        var pos = 0

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        if (yPixelStride == 1) {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(yuvBytes, pos, width)
                pos += width
            }
        } else {
            for (row in 0 until height) {
                for (col in 0 until width) {
                    yuvBytes[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                }
            }
        }

        // NV21 chroma 起点（Y 数据后），需要 width*height/2 字节交错 VU
        val chromaPos = pos

        if (image.planes.size == 2) {
            // ── 2-plane semi-planar（Qualcomm / MTK / USB UVC 常见）──
            val uvPlane = image.planes[1]
            val uvBuffer = uvPlane.buffer
            val uvRowStride = uvPlane.rowStride
            val uvPixelStride = uvPlane.pixelStride
            val uvHeight = height / 2
            val uvWidth = width / 2

            if (uvPixelStride == 2 && uvRowStride == width) {
                // 标准 interleaved：VUVU...（多数设备是 NV12 即 UVUV...，需交换）
                val uvSize = uvWidth * uvHeight * 2
                if (uvBuffer.remaining() >= uvSize) {
                    val uvBytes = ByteArray(uvSize)
                    uvBuffer.get(uvBytes)
                    // NV12(UVUV...) → NV21(VUVU...)：每对交换
                    for (i in uvBytes.indices step 2) {
                        if (i + 1 < uvBytes.size) {
                            yuvBytes[chromaPos + i] = uvBytes[i + 1]     // V
                            yuvBytes[chromaPos + i + 1] = uvBytes[i]     // U
                        }
                    }
                }
            } else {
                // 非标准 stride：逐像素复制
                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        val uvIdx = chromaPos + row * width + col * 2
                        if (uvIdx + 1 < yuvBytes.size) {
                            val u = uvBuffer.get(row * uvRowStride + col * uvPixelStride)
                            val v = uvBuffer.get(row * uvRowStride + col * uvPixelStride + 1)
                            yuvBytes[uvIdx] = v       // V
                            yuvBytes[uvIdx + 1] = u   // U
                        }
                    }
                }
            }
        } else if (image.planes.size >= 3) {
            // ── 3-plane planar：plane[1]=U, plane[2]=V ──
            val uvHeight = height / 2
            val uvWidth = width / 2

            for (uvPlaneIdx in 1..2) {
                val plane = image.planes[uvPlaneIdx]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val isUPlane = uvPlaneIdx == 1
                // NV21: V at even offset, U at odd offset from chromaPos
                val nv21Offset = if (isUPlane) 1 else 0

                Log.d(TAG, "UV plane $uvPlaneIdx: remaining=${buffer.remaining()}, " +
                    "rowStride=$rowStride, pixelStride=$pixelStride, width=$width, " +
                    "uvWidth=$uvWidth, uvHeight=$uvHeight")

                if (pixelStride == 2 && rowStride == width) {
                    // 逐对读取：只取每对的第一个有效 sample
                    for (row in 0 until uvHeight) {
                        for (col in 0 until uvWidth) {
                            val chromaIdx = chromaPos + row * width + col * 2 + nv21Offset
                            if (chromaIdx < yuvBytes.size) {
                                yuvBytes[chromaIdx] = buffer.get(
                                    row * rowStride + col * pixelStride)
                            }
                        }
                    }
                } else if (pixelStride == 1) {
                    for (row in 0 until uvHeight) {
                        for (col in 0 until uvWidth) {
                            val chromaIdx = chromaPos + row * width + col * 2 + nv21Offset
                            if (chromaIdx < yuvBytes.size) {
                                yuvBytes[chromaIdx] = buffer.get(
                                    row * rowStride + col * pixelStride)
                            }
                        }
                    }
                } else {
                    for (row in 0 until uvHeight) {
                        for (col in 0 until uvWidth) {
                            val chromaIdx = chromaPos + row * width + col * 2 + nv21Offset
                            if (chromaIdx < yuvBytes.size) {
                                yuvBytes[chromaIdx] = buffer.get(
                                    row * rowStride + col * pixelStride)
                            }
                        }
                    }
                }
            }
        }
        // else: planes.size < 2 — 异常，chroma 保持零值（输出灰度图，不崩溃）

        val out = ByteArrayOutputStream()
        val yuvImage = YuvImage(yuvBytes, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun saveToMediaStore(
        bytes: ByteArray,
        onSuccess: (android.net.Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val name = "MT_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_PATH)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: run {
                onError("创建相册文件失败")
                return
            }

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                onError("写入相册文件失败")
                return
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }

            Log.i(TAG, "照片已保存: $uri")
            onSuccess(uri)

            Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存失败: ${e.message}")
            onError("保存照片失败: ${e.message}")
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        // 释放可能遗留的 semaphore permit
        cameraOpenLock.release()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = HandlerThread("Camera2Capture").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }
}
