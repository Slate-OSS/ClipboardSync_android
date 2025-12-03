package com.finitecode.clipboardsync

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json

object MessageProtocol {

    // Create clipboard update message
    fun createClipboardMessage(
        item: ClipboardItem,
        fromDeviceId: String,
        toDeviceId: String,
        encryptionKey: ByteArray
    ): SyncMessage? {
        return try {
            // Serialize clipboard item
            val itemJson = Json.encodeToString(ClipboardItem.serializer(), item)
            val itemData = itemJson.toByteArray()

            // Encrypt
            val encryptedData = EncryptionManager.encrypt(itemData, encryptionKey)

            // Base64 encode
            val payload = Base64.encodeToString(encryptedData, Base64.NO_WRAP)

            SyncMessage(
                type = "clipboard_update",
                fromDeviceId = fromDeviceId,
                toDeviceId = toDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        } catch (e: Exception) {
            Log.e("MessageProtocol", "❌ Failed to create clipboard message: ${e.message}")
            null
        }
    }

    // Decode clipboard message
    fun decodeClipboardMessage(
        message: SyncMessage,
        decryptionKey: ByteArray
    ): ClipboardItem? {
        return try {
            // Base64 decode
            val encryptedData = Base64.decode(message.payload, Base64.NO_WRAP)

            // Decrypt
            val decryptedData = EncryptionManager.decrypt(encryptedData, decryptionKey)

            // Deserialize
            val itemJson = String(decryptedData)
            Json.decodeFromString(ClipboardItem.serializer(), itemJson)
        } catch (e: Exception) {
            Log.e("MessageProtocol", "❌ Failed to decode clipboard message: ${e.message}")
            null
        }
    }
}
