package com.finitecode.clipboardsync

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

class DevicePairingManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("clipboard_sync", Context.MODE_PRIVATE)
    private val json = Json

    val pairedDevices = mutableStateListOf<PairedDevice>()
    val currentDeviceId = mutableStateOf(getCurrentDeviceId(context))
    val syncStatus = mutableStateOf("")

    init {
        loadPairedDevices()
    }

    private fun getCurrentDeviceId(context: Context): String {
        val existing = prefs.getString("currentDeviceId", null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("currentDeviceId", newId).apply()
        return newId
    }

    /**
     * Parse and add paired device from pairing code
     */
    fun addPairedDevice(pairingCode: String): Pair<Boolean, String> {
        val components = pairingCode.split("|")
        if (components.size != 3) {
            val msg = "❌ Invalid pairing code format"
            println(msg)
            return Pair(false, msg)
        }

        val deviceId = components[0]
        val keyHex = components[2]

        // Prevent self-pairing
        if (deviceId == currentDeviceId.value) {
            val msg = "❌ Cannot pair device with itself"
            println(msg)
            return Pair(false, msg)
        }

        // Check if already paired
        if (pairedDevices.any { it.remoteDeviceId == deviceId }) {
            val msg = "⚠️ Device already paired"
            println(msg)
            return Pair(false, msg)
        }

        val device = PairedDevice(
            id = UUID.randomUUID().toString(),
            remoteDeviceId = deviceId,
            sharedKeyHex = keyHex,
            name = "Mac Device",
            dateAdded = System.currentTimeMillis()
        )

        pairedDevices.add(device)
        savePairedDevices()

        val msg = "✅ Device paired: ${deviceId.take(8)}..."
        println(msg)
        return Pair(true, msg)
    }

    fun removePairedDevice(device: PairedDevice) {
        pairedDevices.remove(device)
        savePairedDevices()
        println("🗑️ Device removed: ${device.remoteDeviceId.take(8)}...")
    }

    private fun savePairedDevices() {
        val jsonString = json.encodeToString(
            ListSerializer(PairedDevice.serializer()),
            pairedDevices.toList()
        )
        prefs.edit().putString("pairedDevices", jsonString).apply()
        println("💾 Paired devices saved")
    }

    private fun loadPairedDevices() {
        val json = prefs.getString("pairedDevices", null) ?: return
        try {
            val devices = Json.decodeFromString<List<PairedDevice>>(json)
            pairedDevices.clear()
            pairedDevices.addAll(devices)
            println("📂 Loaded ${pairedDevices.size} paired devices")
        } catch (e: Exception) {
            println("❌ Failed to load devices: ${e.message}")
        }
    }
}

@Serializable
data class PairedDevice(
    val id: String,
    val remoteDeviceId: String,
    val sharedKeyHex: String,
    val name: String,
    val dateAdded: Long
)
