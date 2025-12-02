package com.finitecode.clipboardsync

import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ClipboardManagerWrapper(private val context: Context) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val clipboardContent = mutableStateOf("")
    val clipboardHistory = mutableStateListOf<ClipboardItem>()
    val isMonitoring = mutableStateOf(false)

    fun startMonitoring() {
        isMonitoring.value = true
        getCurrentClipboard()

        // Poll for changes every 500ms
        Thread {
            while (isMonitoring.value) {
                val current = getCurrentClipboard()
                if (current != clipboardContent.value) {
                    clipboardContent.value = current
                    val item = ClipboardItem(
                        content = current,
                        timestamp = System.currentTimeMillis(),
                        type = "text"
                    )
                    clipboardHistory.add(0, item)
                    if (clipboardHistory.size > 50) {
                        clipboardHistory.removeAt(clipboardHistory.size - 1)
                    }
                    println("📋 Clipboard changed: ${current.take(50)}...")
                }
                Thread.sleep(500)
            }
        }.start()

        println("✅ Clipboard monitoring started")
    }

    fun stopMonitoring() {
        isMonitoring.value = false
        println("⏹️ Clipboard monitoring stopped")
    }

    private fun getCurrentClipboard(): String {
        val item = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return item
    }

    fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("clipboard", text)
        clipboardManager.setPrimaryClip(clip)
        println("✅ Copied to clipboard")
    }
}

@Serializable
data class ClipboardItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val timestamp: Long,
    val type: String
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): ClipboardItem? = try {
            Json.decodeFromString(serializer(), json)
        } catch (e: Exception) {
            null
        }
    }
}
