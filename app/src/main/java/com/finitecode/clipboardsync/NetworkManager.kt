package com.finitecode.clipboardsync

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit

class NetworkManager(
    private val deviceId: String,
    private val onMessageReceived: (SyncMessage) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var isManualDisconnect = false

    var connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set

    var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null

    // Connect to Mac server
    fun connect(macIp: String, port: Int = 8765, attempt: Int = 1) {
        if (attempt == 1) {
            isManualDisconnect = false
        }
        Log.d(TAG, "🔗 Connecting to ws://$macIp:$port (attempt #$attempt)")

        val request = Request.Builder()
            .url("ws://$macIp:$port")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                reconnectJob?.cancel() // Cancel any reconnect jobs
                updateStatus(ConnectionStatus.CONNECTED)

                // Send handshake immediately
                sendHandshake()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 Received text: ${text.take(100)}")
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                try {
                    val text = bytes.utf8()
                    Log.d(TAG, "📨 Received bytes: ${text.take(100)}")
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding UTF-8 from bytes: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket failure: ${t.message}")
                updateStatus(ConnectionStatus.ERROR(t.message ?: "Connection failed"))

                // Auto-reconnect unless manual disconnect
                if (!isManualDisconnect) {
                    scheduleReconnect(macIp, port, attempt)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🛑 WebSocket closed: $code - $reason")
                updateStatus(ConnectionStatus.DISCONNECTED)

                // Auto-reconnect unless manual disconnect
                if (!isManualDisconnect) {
                    scheduleReconnect(macIp, port, attempt)
                }
            }
        })

        updateStatus(ConnectionStatus.CONNECTING)
    }

    fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
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

    // Send sync message
    fun sendMessage(message: SyncMessage): Boolean {
        val ws = webSocket ?: run {
            Log.e(TAG, "❌ WebSocket not connected")
            return false
        }

        return try {
            val json = Json.encodeToString(message)
            ws.send(json)
            Log.d(TAG, "✅ Sent ${message.type} message")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send failed: ${e.message}")
            false
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
        }
    }

    // Schedule exponential backoff reconnect
    private fun scheduleReconnect(macIp: String, port: Int, attempt: Int) {
        reconnectJob?.cancel()

        val nextAttempt = attempt + 1
        val delay = Math.min(2000L * (1 shl (attempt - 1)), 30000L) // Max 30s
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
