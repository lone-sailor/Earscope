package com.vgc.earscope

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class StreamState { STOPPED, CONNECTING, STREAMING, STALLED, ERROR }

@Suppress("SpellCheckingInspection")
class CameraStream(private val cameraIp: String) {
    companion object {
        private const val TAG = "CameraStream"
        private const val CAMERA_PORT = 8031
        private const val CONTROL_PORT = 50000
        private const val HEADER_LEN = 24
        private const val MAGIC: Byte = 0x66
        private const val STALL_AFTER_MS = 2000L

        private val TRIGGER = byteArrayOf(
            0x99.toByte(), 0x99.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        private val SETCMD_PREFIX = "SETCMD".toByteArray(Charsets.US_ASCII)
        private val SETCMD_SUFFIX = byteArrayOf(
            0x00, 0x00, 0x90.toByte(), 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var videoJob: Job? = null
    private var heartbeatJob: Job? = null
    private var fpsJob: Job? = null

    private val _state = MutableStateFlow(StreamState.STOPPED)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isLowBattery = MutableStateFlow(false)
    val isLowBattery: StateFlow<Boolean> = _isLowBattery.asStateFlow()

    @Volatile
    private var frameCount = 0

    fun start() {
        if (videoJob?.isActive == true) return
        _errorMessage.value = ""
        _batteryLevel.value = -1
        _isLowBattery.value = false
        _state.value = StreamState.CONNECTING
        videoJob = scope.launch { runVideoLoop() }
        heartbeatJob = scope.launch { runHeartbeatLoop() }
        fpsJob = scope.launch { runFpsCounter() }
    }

    fun stop() {
        videoJob?.cancel()
        heartbeatJob?.cancel()
        fpsJob?.cancel()
        _state.value = StreamState.STOPPED
        _errorMessage.value = ""
        _batteryLevel.value = -1
        _isLowBattery.value = false
        _latestFrame.value = null
    }

    private fun createCleanSocket(timeout: Int): DatagramSocket {
        return DatagramSocket().apply {
            broadcast = true
            reuseAddress = true
            soTimeout = timeout
        }
    }

    private fun mapExceptionToUserMessage(e: Throwable): String {
        return when (e) {
            is UnknownHostException -> "Cannot resolve IP address. Check the camera IP format."
            is NoRouteToHostException -> "No route to camera. Ensure your phone is connected to the camera's Wi-Fi."
            is PortUnreachableException -> "Camera port unreachable. The device might still be booting up."
            is BindException -> "Local network port is already in use by another service."
            is SocketTimeoutException -> "Connection timed out. No response received from camera."
            is SocketException -> {
                val msg = e.message ?: ""
                when {
                    msg.contains("EPERM", ignoreCase = true) -> "Network permission denied by system. Grant location/Wi-Fi access."
                    msg.contains("ENETUNREACH", ignoreCase = true) -> "Network is unreachable. Reconnect to the camera's Wi-Fi."
                    msg.contains("EHOSTUNREACH", ignoreCase = true) -> "Camera host unreachable. Verify the camera is powered on."
                    else -> "Socket error: ${e.localizedMessage ?: "Unexpected network drop"}"
                }
            }
            is IOException -> "I/O error communicating with camera: ${e.localizedMessage}"
            else -> "Error: ${e.localizedMessage ?: "An unexpected error occurred"}"
        }
    }

    private suspend fun runHeartbeatLoop() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = createCleanSocket(timeout = 60)
            val address = InetAddress.getByName(cameraIp)
            var seq = 1
            val replyBuf = ByteArray(256)
            val replyPacket = DatagramPacket(replyBuf, replyBuf.size)

            while (isActive) {
                try {
                    val buffer = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.put(SETCMD_PREFIX)
                    buffer.putShort((seq and 0xFFFF).toShort())
                    buffer.put(SETCMD_SUFFIX)

                    val packet = DatagramPacket(buffer.array(), 18, address, CONTROL_PORT)
                    socket.send(packet)

                    try {
                        socket.receive(replyPacket)
                    } catch (_: SocketTimeoutException) {}
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.d(TAG, "Heartbeat error: ${e.message}")
                }

                seq = (seq + 1) and 0xFFFF
                delay(80.milliseconds)
            }
        } catch (e: Exception) {
            if (isActive) {
                Log.e(TAG, "Heartbeat setup failed: ${e.message}", e)
            }
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun runVideoLoop() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        var address: InetAddress? = null
        val chunks = mutableMapOf<Int, ByteArray>()
        var expectedSize: Int? = null
        var frameStartedAt = 0L
        var lastGoodFrameAt = System.currentTimeMillis()
        var lastKeepalive = 0L

        val recvBuf = ByteArray(65536)
        val packet = DatagramPacket(recvBuf, recvBuf.size)

        while (isActive) {
            try {
                if (socket == null || socket.isClosed) {
                    val newAddress = InetAddress.getByName(cameraIp)
                    address = newAddress
                    val newSocket = createCleanSocket(timeout = 500)
                    newSocket.send(DatagramPacket(TRIGGER, TRIGGER.size, newAddress, CAMERA_PORT))
                    socket = newSocket
                    lastKeepalive = System.currentTimeMillis()
                }

                val activeSocket = socket ?: continue
                val targetAddress = address ?: continue

                try {
                    activeSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    try {
                        activeSocket.send(DatagramPacket(TRIGGER, TRIGGER.size, targetAddress, CAMERA_PORT))
                    } catch (sendEx: Exception) {
                        throw sendEx
                    }
                    chunks.clear()
                    expectedSize = null
                    if (System.currentTimeMillis() - lastGoodFrameAt > STALL_AFTER_MS) {
                        _state.value = StreamState.STALLED
                    }
                    continue
                }

                val now = System.currentTimeMillis()
                val len = packet.length

                if (frameStartedAt > 0 && (now - frameStartedAt) > 500) {
                    chunks.clear()
                    expectedSize = null
                }

                if (now - lastKeepalive > 1000) {
                    try {
                        activeSocket.send(DatagramPacket(TRIGGER, TRIGGER.size, targetAddress, CAMERA_PORT))
                    } catch (sendEx: Exception) {
                        throw sendEx
                    }
                    lastKeepalive = now
                }

                if (len < HEADER_LEN || recvBuf[0] != MAGIC) continue

                val flag = recvBuf[1].toInt()
                val bb = ByteBuffer.wrap(recvBuf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
                val frameSize = bb.getShort(4).toInt() and 0xFFFF
                val idx = bb.getShort(12).toInt() and 0xFFFF
                val payloadLen = bb.getShort(14).toInt() and 0xFFFF
                val payload = recvBuf.copyOfRange(HEADER_LEN, len)

                if (flag == 0x01) {
                    val rawGrade = recvBuf[16].toInt() and 0xFF
                    val isCritical = rawGrade in 1..2
                    _isLowBattery.value = isCritical
                    _batteryLevel.value = when {
                        isCritical -> 10
                        rawGrade in 3..4 -> 30
                        rawGrade in 5..7 -> 70
                        rawGrade >= 8 -> 100
                        else -> -1
                    }
                }

                if (payload.size != payloadLen) {
                    chunks.clear()
                    expectedSize = null
                    continue
                }

                if (flag == 0x01) {
                    chunks.clear()
                    chunks[idx] = payload
                    expectedSize = frameSize
                    frameStartedAt = now
                    continue
                }

                if ((flag != 0x02 && flag != 0x03) || expectedSize == null) continue

                chunks[idx] = payload

                if (flag == 0x02) {
                    val maxIdx = chunks.keys.maxOrNull() ?: 0
                    val isComplete = (0..maxIdx).all { chunks.containsKey(it) }

                    if (isComplete) {
                        val frameStream = ByteArrayOutputStream(expectedSize)
                        for (i in 0..maxIdx) {
                            val part = chunks[i] ?: break
                            frameStream.write(part)
                        }

                        val totalBytes = frameStream.toByteArray()

                        if (totalBytes.size == expectedSize && totalBytes[0] == 0xFF.toByte() && totalBytes[1] == 0xD8.toByte()) {
                            launch(Dispatchers.Default) {
                                try {
                                    val bitmap = BitmapFactory.decodeByteArray(totalBytes, 0, totalBytes.size)
                                    if (bitmap != null) {
                                        _latestFrame.value = bitmap
                                        frameCount++
                                        lastGoodFrameAt = now
                                        _state.value = StreamState.STREAMING
                                        _errorMessage.value = ""
                                    }
                                } catch (_: OutOfMemoryError) {
                                    Log.w(TAG, "Dropped frame due to memory constraint")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Frame decode failed: ${e.message}")
                                }
                            }
                        }
                    }
                    chunks.clear()
                    expectedSize = null
                }
            } catch (e: Exception) {
                if (!isActive) break
                val userMsg = mapExceptionToUserMessage(e)
                Log.e(TAG, "Socket loop encountered: ${e.javaClass.simpleName} - $userMsg", e)
                _errorMessage.value = userMsg
                _state.value = StreamState.ERROR

                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null
                chunks.clear()
                expectedSize = null
                delay(1.seconds)
            }
        }
        try {
            socket?.close()
        } catch (_: Exception) {}
    }

    private suspend fun runFpsCounter() = withContext(Dispatchers.Default) {
        var lastSeen = 0
        while (isActive) {
            delay(1.seconds)
            _fps.value = frameCount - lastSeen
            lastSeen = frameCount
        }
    }
}