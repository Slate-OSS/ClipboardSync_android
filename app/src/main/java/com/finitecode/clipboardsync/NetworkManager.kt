package com.finitecode.clipboardsync

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.net.Socket
import java.net.SocketException
import kotlin.math.min

class NetworkManager(
    private val deviceId: String,
    private val onMessageReceived: (SyncMessage) -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private var isManualDisconnect = false

    private var lastMacIp: String? = null
    private var lastPort: Int = 8765

    var connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set

    var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null

    // Connect to Mac server using plain TCP
    fun connect(macIp: String, port: Int = 8765, attempt: Int = 1) {
        if (attempt == 1) {
            isManualDisconnect = false
            lastMacIp = macIp
            lastPort = port
        }

        updateStatus(ConnectionStatus.CONNECTING)
        Log.d(TAG, "🔗 Connecting to TCP $macIp:$port (attempt #$attempt)")

        scope.launch {
            try {
                // Create plain TCP socket (matches Mac server)
                socket = Socket(macIp, port).apply {
                    soTimeout = 60000 // 60s read timeout
                    keepAlive = true
                    tcpNoDelay = true
                }

                outputStream = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
                inputStream = DataInputStream(BufferedInputStream(socket!!.getInputStream()))

                Log.d(TAG, "✅ TCP connected to $macIp:$port")
                reconnectJob?.cancel()
                updateStatus(ConnectionStatus.CONNECTED)

                // Send handshake immediately
                sendHandshake()

                // Start ping keep-alive
                startPingKeepAlive()

                // Start receive loop
                startReceiving()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Connection failed: ${e.message}")
                updateStatus(ConnectionStatus.ERROR(e.message ?: "Connection failed"))

                // Auto-reconnect unless manual disconnect
                if (!isManualDisconnect) {
                    scheduleReconnect(macIp, port, attempt)
                }
            }
        }
    }

    fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
        receiveJob?.cancel()
        pingJob?.cancel()

        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }

        socket = null
        outputStream = null
        inputStream = null

        updateStatus(ConnectionStatus.DISCONNECTED)
        Log.d(TAG, "🛑 Disconnected")
    }

    // Send handshake message
    private fun sendHandshake() {
        val handshake = SyncMessage(
            type = "handshake",
            fromDeviceId = deviceId,
            toDeviceId = null,
            timestamp = System.currentTimeMillis(),
            payload = ""
        )
        sendMessage(handshake)
        Log.d(TAG, "🤝 Sent handshake")
    }

    // Keep-alive ping every 30 seconds
    private fun startPingKeepAlive() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && socket?.isConnected == true) {
                delay(30000) // 30 seconds

                val ping = SyncMessage(
                    type = "ping",
                    fromDeviceId = deviceId,
                    toDeviceId = null,
                    timestamp = System.currentTimeMillis(),
                    payload = ""
                )

                if (!sendMessage(ping)) {
                    Log.w(TAG, "⚠️ Ping failed - connection may be dead")
                    break
                }
            }
        }
    }

    // Send sync message with length-prefix framing
    fun sendMessage(message: SyncMessage): Boolean {
        val out = outputStream ?: run {
            Log.e(TAG, "❌ Socket not connected")
            return false
        }

        return try {
            val json = Json.encodeToString(message)
            val messageBytes = json.toByteArray(Charsets.UTF_8)

            synchronized(out) {
                // Send 4-byte length prefix (big-endian)
                out.writeInt(messageBytes.size)
                // Send actual message
                out.write(messageBytes)
                out.flush()
            }

            Log.d(TAG, "✅ Sent ${message.type} message (${messageBytes.size} bytes)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Send failed: ${e.message}")
            handleConnectionError(e)
            false
        }
    }

    // Start receiving messages with length-prefix framing
    private fun startReceiving() {
        receiveJob = scope.launch {
            val stream = inputStream ?: return@launch

            try {
                while (isActive && socket?.isConnected == true) {
                    // Read 4-byte length prefix (big-endian)
                    val length = stream.readInt()

                    if (length <= 0 || length > 1_000_000) { // Sanity check: max 1MB
                        Log.e(TAG, "❌ Invalid message length: $length")
                        break
                    }

                    // Read exact message bytes
                    val messageBytes = ByteArray(length)
                    stream.readFully(messageBytes)

                    // Decode and handle
                    val json = String(messageBytes, Charsets.UTF_8)
                    Log.d(TAG, "📨 Received ${messageBytes.size} bytes")
                    handleMessage(json)
                }
            } catch (e: SocketException) {
                Log.e(TAG, "🔌 Socket closed: ${e.message}")
            } catch (e: EOFException) {
                Log.e(TAG, "🔌 Connection closed by server")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Receive error: ${e.message}")
                e.printStackTrace()
            } finally {
                if (!isManualDisconnect) {
                    handleConnectionError(Exception("Connection closed"))
                }
            }
        }
    }

    // Handle incoming message
    private fun handleMessage(text: String) {
        try {
            val message = Json.decodeFromString<SyncMessage>(text)
            Log.d(TAG, "📥 Decoded message: ${message.type}")

            scope.launch {
                onMessageReceived(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to decode message: ${e.message}")
            Log.e(TAG, "Raw: ${text.take(100)}")
        }
    }

    // Handle connection errors
    private fun handleConnectionError(error: Exception) {
        if (isManualDisconnect) return

        Log.w(TAG, "🔄 Handling connection error, will reconnect")
        updateStatus(ConnectionStatus.ERROR(error.message ?: "Unknown error"))

        val ip = lastMacIp
        val port = lastPort

        disconnect()

        // Auto-reconnect
        if (!isManualDisconnect && ip != null) {
            scheduleReconnect(ip, port, 1)
        }
    }

    // Schedule exponential backoff reconnect
    private fun scheduleReconnect(macIp: String, port: Int, attempt: Int) {
        reconnectJob?.cancel()

        val nextAttempt = attempt + 1
        val delay = min(2000L * (1 shl (attempt - 1)), 30000L) // Max 30s
        Log.d(TAG, "⏱️ Scheduling reconnect in ${delay}ms (attempt #$nextAttempt)")

        reconnectJob = scope.launch {
            delay(delay)
            if (!isManualDisconnect) {
                connect(macIp, port, nextAttempt)
            }
        }
    }

    private fun updateStatus(status: ConnectionStatus) {
        connectionStatus = status
        onConnectionStatusChanged?.invoke(status)
    }

    fun cleanup() {
        scope.cancel()
        disconnect()
    }

    companion object {
        private const val TAG = "NetworkManager"
    }
}

sealed class ConnectionStatus {
    object DISCONNECTED : ConnectionStatus()
    object CONNECTING : ConnectionStatus()
    object CONNECTED : ConnectionStatus()
    data class ERROR(val message: String) : ConnectionStatus()

    fun displayString(): String = when (this) {
        is DISCONNECTED -> "Disconnected"
        is CONNECTING -> "Connecting..."
        is CONNECTED -> "Connected"
        is ERROR -> "Error: $message"
    }
}

@Serializable
data class SyncMessage(
    val type: String,
    val fromDeviceId: String,
    val toDeviceId: String?,
    val timestamp: Long,
    val payload: String
)
