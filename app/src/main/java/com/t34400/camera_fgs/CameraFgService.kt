package com.t34400.camera_fgs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground Service that opens BOTH passthrough RGB cameras (left/right) on Quest
 * and exposes two independent streams per camera:
 *
 *  1) Raw H.264 (AnnexB) over TCP
 *  2) JPEG frames over TCP (length-prefixed)
 *
 * Ports on the headset (loopback):
 *   Left  H.264: 8081   Right H.264: 8082
 *   Left  JPEG: 8091   Right JPEG: 8092
 *
 * PC side examples:
 *   adb forward tcp:18081 tcp:8081
 *   adb forward tcp:18082 tcp:8082
 *   ffplay -fflags nobuffer -flags low_delay -framedrop -analyzeduration 2000000 -probesize 2000000 -f h264 tcp://127.0.0.1:18081
 *
 * JPEG stream is for quick/debug visualization (e.g., Unity C# can LoadImage on each frame):
 *   adb forward tcp:19091 tcp:8091
 *   adb forward tcp:19092 tcp:8092
 */
class CameraFgService : Service() {

    private val tag = "DualCamFgSvc"
    private val channelId = "camera_fgs"
    private val notifId = 3

    private val leftH264Port = 8081
    private val rightH264Port = 8082
    private val leftJpegPort = 8091
    private val rightJpegPort = 8092

    // Start fixed; can be made dynamic per camera.
    private val width = 1280
    private val height = 960
    private val fps = 30
    private val iFrameIntervalSec = 1
    private val bitrate = 4_000_000

    // JPEG is intentionally throttled to reduce CPU/USB load.
    private val jpegMaxFps = 10
    private val jpegQuality = 80

    private lateinit var cameraManager: CameraManager

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var leftPipeline: CameraPipeline? = null
    private var rightPipeline: CameraPipeline? = null

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()
        startInForeground()
        Log.i(tag, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "onStartCommand flags=$flags startId=$startId")

        val ids = try {
            cameraManager.cameraIdList.toList()
        } catch (e: Exception) {
            Log.e(tag, "cameraIdList error", e)
            return START_NOT_STICKY
        }

        val leftId = selectPassthroughCameraId(ids, position = 0)
        val rightId = selectPassthroughCameraId(ids, position = 1)

        Log.i(tag, "Selected cameraIds left=$leftId right=$rightId all=${ids.joinToString()}")

        val handler = bgHandler ?: run {
            Log.e(tag, "bgHandler is null")
            return START_NOT_STICKY
        }

        leftPipeline?.stop()
        rightPipeline?.stop()

        if (leftId != null) {
            leftPipeline = CameraPipeline(
                label = "left",
                cameraId = leftId,
                h264Port = leftH264Port,
                jpegPort = leftJpegPort,
                width = width,
                height = height,
                fps = fps,
                iFrameIntervalSec = iFrameIntervalSec,
                bitrate = bitrate,
                jpegMaxFps = jpegMaxFps,
                jpegQuality = jpegQuality,
                cameraManager = cameraManager,
                handler = handler
            ).also { it.start() }
        } else {
            Log.w(tag, "Left passthrough camera not found")
        }

        if (rightId != null) {
            rightPipeline = CameraPipeline(
                label = "right",
                cameraId = rightId,
                h264Port = rightH264Port,
                jpegPort = rightJpegPort,
                width = width,
                height = height,
                fps = fps,
                iFrameIntervalSec = iFrameIntervalSec,
                bitrate = bitrate,
                jpegMaxFps = jpegMaxFps,
                jpegQuality = jpegQuality,
                cameraManager = cameraManager,
                handler = handler
            ).also { it.start() }
        } else {
            Log.w(tag, "Right passthrough camera not found")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(tag, "onDestroy")
        leftPipeline?.stop()
        rightPipeline?.stop()
        leftPipeline = null
        rightPipeline = null
        stopBackgroundThread()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Camera Foreground Service",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dual camera streaming")
            .setContentText("H264 L:$leftH264Port R:$rightH264Port | JPEG L:$leftJpegPort R:$rightJpegPort")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        startForeground(notifId, notification)
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("camera-bg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            bgThread?.quitSafely()
            bgThread?.join()
        } catch (_: Exception) {
        } finally {
            bgThread = null
            bgHandler = null
        }
    }

    private fun selectPassthroughCameraId(ids: List<String>, position: Int): String? {
        ids.firstOrNull { id ->
            try {
                val ch = cameraManager.getCameraCharacteristics(id)
                val src = readMetaInt(ch, "com.meta.extra_metadata.camera_source")
                val pos = readMetaInt(ch, "com.meta.extra_metadata.position")
                src == 0 && pos == position
            } catch (_: Exception) {
                false
            }
        }?.let { return it }

        ids.firstOrNull { id ->
            try {
                val ch = cameraManager.getCameraCharacteristics(id)
                readMetaInt(ch, "com.meta.extra_metadata.camera_source") == 0
            } catch (_: Exception) {
                false
            }
        }?.let { return it }

        return null
    }

    private fun readMetaInt(ch: CameraCharacteristics, name: String): Int? {
        return try {
            val key = CameraCharacteristics.Key(name, Int::class.javaObjectType)
            ch.get(key)
        } catch (_: Exception) {
            null
        }
    }
}

private class CameraPipeline(
    private val label: String,
    private val cameraId: String,
    private val h264Port: Int,
    private val jpegPort: Int,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val iFrameIntervalSec: Int,
    private val bitrate: Int,
    private val jpegMaxFps: Int,
    private val jpegQuality: Int,
    private val cameraManager: CameraManager,
    private val handler: Handler,
) {

    private val tag = "Pipe-$label"
    private val running = AtomicBoolean(false)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var codec: MediaCodec? = null
    private var codecInputSurface: Surface? = null

    private var jpegReader: ImageReader? = null

    // H.264 TCP server
    private var h264ServerSocket: ServerSocket? = null
    private var h264ClientSocket: Socket? = null
    private var h264Out: BufferedOutputStream? = null
    private var h264ServerThread: Thread? = null
    private var drainThread: Thread? = null

    // JPEG TCP server (length-prefixed frames)
    private var jpegServerSocket: ServerSocket? = null
    private var jpegClientSocket: Socket? = null
    private var jpegOut: BufferedOutputStream? = null
    private var jpegServerThread: Thread? = null

    private val configLock = Any()
    @Volatile private var spsPpsAnnexB: ByteArray? = null

    @Volatile private var lastJpegSendNs: Long = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startH264Server()
        startJpegServer()
        openCamera()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopStreaming()
        stopH264Server()
        stopJpegServer()
    }

    private fun startH264Server() {
        h264ServerThread = thread(name = "h264-server-$label", isDaemon = true) {
            try {
                h264ServerSocket = ServerSocket(h264Port, 1)
                Log.i(tag, "H.264 server on 127.0.0.1:$h264Port")
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val s = h264ServerSocket?.accept() ?: break
                    synchronized(this) {
                        h264Out?.flush()
                        h264Out?.close()
                        h264ClientSocket?.close()
                        h264ClientSocket = s
                        h264Out = BufferedOutputStream(s.getOutputStream())
                    }
                    Log.i(tag, "H.264 client connected: ${s.inetAddress}:${s.port}")

                    val cfg = synchronized(configLock) { spsPpsAnnexB }
                    if (cfg != null) {
                        try {
                            synchronized(this) { h264Out }?.apply {
                                write(cfg)
                                flush()
                            }
                            Log.i(tag, "H.264: sent cached SPS/PPS (${cfg.size} bytes)")
                        } catch (e: Exception) {
                            Log.w(tag, "H.264: failed to send cached SPS/PPS", e)
                        }
                    }

                    requestSyncFrame()
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(tag, "H.264 server error", e)
            }
        }
    }

    private fun stopH264Server() {
        try {
            synchronized(this) {
                h264Out?.flush()
                h264Out?.close()
                h264ClientSocket?.close()
                h264Out = null
                h264ClientSocket = null
            }
        } catch (_: Exception) {
        }

        try {
            h264ServerSocket?.close()
            h264ServerSocket = null
        } catch (_: Exception) {
        }

        h264ServerThread?.interrupt()
        h264ServerThread = null
    }

    private fun startJpegServer() {
        jpegServerThread = thread(name = "jpeg-server-$label", isDaemon = true) {
            try {
                jpegServerSocket = ServerSocket(jpegPort, 1)
                Log.i(tag, "JPEG server on 127.0.0.1:$jpegPort")
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val s = jpegServerSocket?.accept() ?: break
                    synchronized(this) {
                        jpegOut?.flush()
                        jpegOut?.close()
                        jpegClientSocket?.close()
                        jpegClientSocket = s
                        jpegOut = BufferedOutputStream(s.getOutputStream())
                    }
                    Log.i(tag, "JPEG client connected: ${s.inetAddress}:${s.port}")
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(tag, "JPEG server error", e)
            }
        }
    }

    private fun stopJpegServer() {
        try {
            synchronized(this) {
                jpegOut?.flush()
                jpegOut?.close()
                jpegClientSocket?.close()
                jpegOut = null
                jpegClientSocket = null
            }
        } catch (_: Exception) {
        }

        try {
            jpegServerSocket?.close()
            jpegServerSocket = null
        } catch (_: Exception) {
        }

        jpegServerThread?.interrupt()
        jpegServerThread = null
    }

    private fun openCamera() {
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.i(tag, "CameraDevice onOpened id=${device.id}")
                    cameraDevice = device
                    tryStartCodecAndSession(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(tag, "CameraDevice onDisconnected id=${device.id}")
                    device.close()
                    cameraDevice = null
                    stop()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(tag, "CameraDevice onError id=${device.id} error=$error")
                    device.close()
                    cameraDevice = null
                    stop()
                }
            }, handler)
        } catch (se: SecurityException) {
            Log.e(tag, "openCamera SecurityException", se)
        } catch (e: Exception) {
            Log.e(tag, "openCamera error", e)
        }
    }

    private fun tryStartCodecAndSession(device: CameraDevice) {
        try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }

            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = c.createInputSurface()
            c.start()

            codec = c
            codecInputSurface = inputSurface

            jpegReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        handleJpegFrame(img)
                    } catch (e: Exception) {
                        Log.w(tag, "JPEG encode failed", e)
                    } finally {
                        img.close()
                    }
                }, handler)
            }

            drainThread = thread(name = "drain-$label", isDaemon = true) { drainEncoderLoop() }

            createCaptureSession(device, inputSurface, jpegReader!!.surface)
            Log.i(tag, "Encoder started: ${width}x$height ${fps}fps bitrate=$bitrate | JPEG max ${jpegMaxFps}fps")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start codec/session", e)
            stop()
        }
    }

    private fun createCaptureSession(device: CameraDevice, h264Surface: Surface, jpegSurface: Surface) {
        try {
            device.createCaptureSession(
                listOf(h264Surface, jpegSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(tag, "CaptureSession onConfigured")
                        captureSession = session
                        try {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(h264Surface)
                                addTarget(jpegSurface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            }
                            session.setRepeatingRequest(req.build(), null, handler)
                            Log.i(tag, "setRepeatingRequest started")
                        } catch (e: Exception) {
                            Log.e(tag, "setRepeatingRequest error", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(tag, "CaptureSession onConfigureFailed")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(tag, "createCaptureSession error", e)
        }
    }

    private fun drainEncoderLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()

        try {
            while (running.get()) {
                val outIndex = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // no-op
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = c.outputFormat
                        val csd0 = newFormat.getByteBuffer("csd-0")
                        val csd1 = newFormat.getByteBuffer("csd-1")
                        if (csd0 != null && csd1 != null) {
                            val cfg = buildSpsPpsAnnexB(csd0, csd1)
                            synchronized(configLock) { spsPpsAnnexB = cfg }

                            val out = synchronized(this) { h264Out }
                            if (out != null) {
                                try {
                                    out.write(cfg)
                                    out.flush()
                                } catch (e: Exception) {
                                    Log.w(tag, "H.264: failed to send SPS/PPS", e)
                                }
                            }
                        }
                    }

                    outIndex >= 0 -> {
                        val outBuf = c.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            writeH264SampleAuto(outBuf.slice())
                        }
                        c.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(tag, "drainEncoderLoop error", e)
        }
    }

    private fun buildSpsPpsAnnexB(csd0: ByteBuffer, csd1: ByteBuffer): ByteArray {
        val a = csd0.duplicate()
        val b = csd1.duplicate()
        val out = ByteArrayOutputStream()

        fun writeWithStartCode(buf: ByteBuffer) {
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)

            val looksAnnexB = bytes.size >= 4 &&
                    bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                    ((bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) || bytes[2] == 1.toByte())

            if (looksAnnexB) {
                out.write(bytes)
            } else {
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(bytes)
            }
        }

        writeWithStartCode(a)
        writeWithStartCode(b)
        return out.toByteArray()
    }

    private fun requestSyncFrame() {
        val c = codec ?: return
        try {
            val b = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            c.setParameters(b)
        } catch (_: Exception) {
        }
    }

    private fun writeH264SampleAuto(sample: ByteBuffer) {
        val out = synchronized(this) { h264Out } ?: return

        fun looksLikeAnnexB(buf: ByteBuffer): Boolean {
            if (buf.remaining() < 4) return false
            val p = buf.position()
            val b0 = buf.get(p)
            val b1 = buf.get(p + 1)
            val b2 = buf.get(p + 2)
            val b3 = buf.get(p + 3)
            return (b0 == 0.toByte() && b1 == 0.toByte() && b2 == 0.toByte() && b3 == 1.toByte()) ||
                    (b0 == 0.toByte() && b1 == 0.toByte() && b2 == 1.toByte())
        }

        try {
            if (looksLikeAnnexB(sample)) {
                val bytes = ByteArray(sample.remaining())
                sample.get(bytes)
                out.write(bytes)
                out.flush()
                return
            }

            while (sample.remaining() >= 4) {
                val len = sample.int
                if (len <= 0 || len > sample.remaining()) break

                out.write(byteArrayOf(0, 0, 0, 1))
                val nal = ByteArray(len)
                sample.get(nal)
                out.write(nal)
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(tag, "H.264 write failed (disconnect?)", e)
            synchronized(this) {
                try { h264Out?.close() } catch (_: Exception) {}
                try { h264ClientSocket?.close() } catch (_: Exception) {}
                h264Out = null
                h264ClientSocket = null
            }
        }
    }

    private fun handleJpegFrame(img: android.media.Image) {
        val now = System.nanoTime()
        val minIntervalNs = 1_000_000_000L / jpegMaxFps.coerceAtLeast(1)
        if (now - lastJpegSendNs < minIntervalNs) return
        lastJpegSendNs = now

        val out = synchronized(this) { jpegOut } ?: return
        val jpegBytes = yuv420ToJpeg(img, jpegQuality)

        try {
            // 4-byte big-endian length prefix + JPEG bytes
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(jpegBytes.size)
            out.write(lenBuf.array())
            out.write(jpegBytes)
            out.flush()
        } catch (e: Exception) {
            Log.w(tag, "JPEG write failed (disconnect?)", e)
            synchronized(this) {
                try { jpegOut?.close() } catch (_: Exception) {}
                try { jpegClientSocket?.close() } catch (_: Exception) {}
                jpegOut = null
                jpegClientSocket = null
            }
        }
    }

    private fun yuv420ToJpeg(img: android.media.Image, quality: Int): ByteArray {
        val nv21 = yuv420888ToNv21(img)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, img.width, img.height, null)
        val baos = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, img.width, img.height), quality.coerceIn(1, 100), baos)
        return baos.toByteArray()
    }

    private fun yuv420888ToNv21(img: android.media.Image): ByteArray {
        val w = img.width
        val h = img.height
        val ySize = w * h
        val uvSize = w * h / 2
        val out = ByteArray(ySize + uvSize)

        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]

        // Y
        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, w, h, out, 0, 1)

        // UV interleaved as VU (NV21)
        val chromaHeight = h / 2
        val chromaWidth = w / 2

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var outPos = ySize
        for (row in 0 until chromaHeight) {
            var uRowStart = row * uRowStride
            var vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                out[outPos++] = vBuf.get(vIndex)
                out[outPos++] = uBuf.get(uIndex)
            }
        }

        return out
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int,
        outPixelStride: Int
    ) {
        val dup = buffer.duplicate()
        var outPos = outOffset
        val row = ByteArray(rowStride)
        for (r in 0 until height) {
            dup.position(r * rowStride)
            dup.get(row, 0, minOf(rowStride, dup.remaining()))
            var inPos = 0
            for (c in 0 until width) {
                out[outPos] = row[inPos]
                outPos += outPixelStride
                inPos += pixelStride
            }
        }
    }

    private fun stopStreaming() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

        try { jpegReader?.close() } catch (_: Exception) {}
        jpegReader = null

        drainThread?.interrupt()
        drainThread = null

        try { codecInputSurface?.release() } catch (_: Exception) {}
        codecInputSurface = null

        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null

        synchronized(configLock) { spsPpsAnnexB = null }
    }
}
