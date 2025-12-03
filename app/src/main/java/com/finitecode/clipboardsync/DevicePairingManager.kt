package com.finitecode.clipboardsync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.UUID

// Custom serializer for ByteArray to hex string
object ByteArrayAsHexStringSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(EncryptionManager.byteArrayToHexString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return EncryptionManager.hexStringToByteArray(decoder.decodeString())
    }
}

class DevicePairingManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("clipboard_sync", Context.MODE_PRIVATE)
    private val pairingPrefs: SharedPreferences = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val pairedDevices = mutableStateListOf<PairedDevice>()
    val currentDeviceId = mutableStateOf(getCurrentDeviceId())
    val syncStatus = mutableStateOf("")

    init {
        loadPairedDevices()
    }

    private fun getCurrentDeviceId(): String {
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
            sharedKey = EncryptionManager.hexStringToByteArray(keyHex),
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

    // Save devices
    private fun savePairedDevices() {
        try {
            val jsonString = json.encodeToString(ListSerializer(PairedDevice.serializer()), pairedDevices.toList())
            pairingPrefs.edit().putString("paired_devices", jsonString).apply()
        } catch (e: Exception) {
            Log.e("DevicePairingManager", "Failed to save devices: ${e.message}")
        }
    }

    // Load devices
    private fun loadPairedDevices() {
        val jsonString = pairingPrefs.getString("paired_devices", null) ?: return

        try {
            val devicesList = json.decodeFromString(ListSerializer(PairedDevice.serializer()), jsonString)
            pairedDevices.clear()
            pairedDevices.addAll(devicesList)
        } catch (e: Exception) {
            Log.e("DevicePairingManager", "Failed to load devices: ${e.message}")
        }
    }
}

@Serializable
data class PairedDevice(
    val id: String = UUID.randomUUID().toString(),
    val remoteDeviceId: String,
    val name: String,
    @Serializable(with = ByteArrayAsHexStringSerializer::class)
    val sharedKey: ByteArray,
    val dateAdded: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PairedDevice

        if (id != other.id) return false
        if (remoteDeviceId != other.remoteDeviceId) return false
        if (name != other.name) return false
        if (!sharedKey.contentEquals(other.sharedKey)) return false
        if (dateAdded != other.dateAdded) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + remoteDeviceId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + sharedKey.contentHashCode()
        result = 31 * result + dateAdded.hashCode()
        return result
    }
}
