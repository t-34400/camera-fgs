package com.t34400.camera_fgs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.*
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground Service that opens BOTH passthrough RGB cameras (left/right) on Quest
 * and streams each as raw H.264 AnnexB over separate TCP ports.
 *
 * PC side:
 *   adb forward tcp:18081 tcp:8081
 *   adb forward tcp:18082 tcp:8082
 *   ffplay -fflags nobuffer -flags low_delay -framedrop -analyzeduration 2000000 -probesize 2000000 -f h264 tcp://127.0.0.1:18081
 *   ffplay -fflags nobuffer -flags low_delay -framedrop -analyzeduration 2000000 -probesize 2000000 -f h264 tcp://127.0.0.1:18082
 */
class CameraFgService : Service() {

    private val tag = "DualCameraFgService"
    private val channelId = "camera_fgs"
    private val notifId = 2

    // Device-side ports (loopback). Expose via adb forward.
    private val leftPort = 8081
    private val rightPort = 8082

    // Fixed start; can be made dynamic by reading StreamConfigurationMap per camera.
    private val width = 1280
    private val height = 960
    private val fps = 30
    private val iFrameIntervalSec = 1
    private val bitrate = 4_000_000

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

        val handler = bgHandler
        if (handler == null) {
            Log.e(tag, "bgHandler is null")
            return START_NOT_STICKY
        }

        if (leftId != null) {
            leftPipeline?.stop()
            leftPipeline = CameraPipeline(
                label = "left",
                cameraId = leftId,
                listenPort = leftPort,
                width = width,
                height = height,
                fps = fps,
                iFrameIntervalSec = iFrameIntervalSec,
                bitrate = bitrate,
                cameraManager = cameraManager,
                handler = handler
            ).also { it.start() }
        } else {
            Log.w(tag, "Left passthrough camera not found")
        }

        if (rightId != null) {
            rightPipeline?.stop()
            rightPipeline = CameraPipeline(
                label = "right",
                cameraId = rightId,
                listenPort = rightPort,
                width = width,
                height = height,
                fps = fps,
                iFrameIntervalSec = iFrameIntervalSec,
                bitrate = bitrate,
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(tag, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
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
            .setContentText("Left:$leftPort Right:$rightPort (adb forward)")
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
        // Primary: Meta vendor tags
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

        // Fallback: still pick any camera_source==0
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
    private val listenPort: Int,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val iFrameIntervalSec: Int,
    private val bitrate: Int,
    private val cameraManager: CameraManager,
    private val handler: Handler,
) {

    private val tag = "CamPipe-$label"

    private val running = AtomicBoolean(false)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var codec: MediaCodec? = null
    private var codecInputSurface: Surface? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOut: BufferedOutputStream? = null

    private var serverThread: Thread? = null
    private var drainThread: Thread? = null

    private val configLock = Any()
    @Volatile private var spsPpsAnnexB: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startTcpServer()
        openCamera()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopStreaming()
        stopTcpServer()
    }

    private fun startTcpServer() {
        serverThread = thread(name = "server-$label", isDaemon = true) {
            try {
                serverSocket = ServerSocket(listenPort, 1)
                Log.i(tag, "TCP server listening on 127.0.0.1:$listenPort")

                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val s = serverSocket?.accept() ?: break
                    synchronized(this) {
                        clientOut?.flush()
                        clientOut?.close()
                        clientSocket?.close()
                        clientSocket = s
                        clientOut = BufferedOutputStream(s.getOutputStream())
                    }
                    Log.i(tag, "Client connected: ${s.inetAddress}:${s.port}")

                    val cfg = synchronized(configLock) { spsPpsAnnexB }
                    if (cfg != null) {
                        try {
                            synchronized(this) { clientOut }?.apply {
                                write(cfg)
                                flush()
                            }
                            Log.i(tag, "Sent cached SPS/PPS (${cfg.size} bytes)")
                        } catch (e: Exception) {
                            Log.w(tag, "Failed to send cached SPS/PPS", e)
                        }
                    }

                    requestSyncFrame()
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(tag, "TCP server error", e)
            }
        }
    }

    private fun stopTcpServer() {
        try {
            synchronized(this) {
                clientOut?.flush()
                clientOut?.close()
                clientSocket?.close()
                clientOut = null
                clientSocket = null
            }
        } catch (_: Exception) {
        }

        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {
        }

        serverThread?.interrupt()
        serverThread = null
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

            drainThread = thread(name = "drain-$label", isDaemon = true) { drainEncoderLoop() }

            createCaptureSession(device, inputSurface)
            Log.i(tag, "Encoder started: ${width}x$height ${fps}fps bitrate=$bitrate")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start codec/session", e)
            stop()
        }
    }

    private fun createCaptureSession(device: CameraDevice, targetSurface: Surface) {
        try {
            device.createCaptureSession(
                listOf(targetSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(tag, "CaptureSession onConfigured")
                        captureSession = session
                        try {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(targetSurface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            }
                            session.setRepeatingRequest(req.build(), null, handler)
                            Log.i(tag, "setRepeatingRequest started (codec surface)")
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

                            val out = synchronized(this) { clientOut }
                            if (out != null) {
                                try {
                                    out.write(cfg)
                                    out.flush()
                                    Log.i(tag, "Sent SPS/PPS to client (format changed)")
                                } catch (e: Exception) {
                                    Log.w(tag, "Failed to send SPS/PPS to client", e)
                                }
                            } else {
                                Log.i(tag, "Cached SPS/PPS (no client yet)")
                            }
                        }
                    }

                    outIndex >= 0 -> {
                        val outBuf = c.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            writeSampleAuto(outBuf.slice())
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
            Log.i(tag, "Requested sync frame")
        } catch (_: Exception) {
        }
    }

    private fun writeSampleAuto(sample: ByteBuffer) {
        val out = synchronized(this) { clientOut } ?: return

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
            Log.w(tag, "Client write failed (disconnect?)", e)
            synchronized(this) {
                try { clientOut?.close() } catch (_: Exception) {}
                try { clientSocket?.close() } catch (_: Exception) {}
                clientOut = null
                clientSocket = null
            }
        }
    }

    private fun stopStreaming() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

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
