package com.finitecode.clipboardsync

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import com.finitecode.clipboardsync.ui.theme.ClipboardSyncTheme
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete

class MainActivity : ComponentActivity() {
    private lateinit var networkManager: NetworkManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clipboardManager = ClipboardManagerWrapper(this)
        val pairingManager = DevicePairingManager(this)

        // Create NetworkManager
        networkManager = NetworkManager(
            deviceId = pairingManager.currentDeviceId.value,
            onMessageReceived = { message ->
                handleIncomingMessage(message, clipboardManager, pairingManager)
            }
        )

        setContent {
            ClipboardSyncTheme {
                ClipboardSyncApp(clipboardManager, pairingManager, networkManager)
            }
        }
    }

    private fun handleIncomingMessage(
        message: SyncMessage,
        clipboardManager: ClipboardManagerWrapper,
        pairingManager: DevicePairingManager
    ) {
        when (message.type) {
            "clipboard_update" -> {
                // Find paired device
                val device = pairingManager.pairedDevices.find {
                    it.remoteDeviceId == message.fromDeviceId
                }

                if (device == null) {
                    println("⚠️ Message from unknown device: ${message.fromDeviceId.take(8)}")
                    return
                }

                // Decrypt and update clipboard
                val item = MessageProtocol.decodeClipboardMessage(message, device.sharedKey)
                if (item != null) {
                    clipboardManager.copyToClipboard(item.content)
                    clipboardManager.clipboardHistory.add(0, item)
                    if (clipboardManager.clipboardHistory.size > 50) {
                        clipboardManager.clipboardHistory.removeAt(50)
                    }
                    println("📥 Received clipboard from ${device.name}")
                }
            }

            "ping" -> {
                println("🏓 Received ping from ${message.fromDeviceId.take(8)}")
            }

            else -> {
                println("⚠️ Unknown message type: ${message.type}")
            }
        }
    }
}

@Composable
fun ClipboardSyncApp(
    clipboardManager: ClipboardManagerWrapper,
    pairingManager: DevicePairingManager,
    networkManager: NetworkManager
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Monitor") },
                    label = { Text("Monitor") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> MonitorScreen(clipboardManager, pairingManager, networkManager)
                1 -> SettingsScreen(pairingManager, networkManager)
            }
        }
    }
}

@Composable
fun MonitorScreen(
    clipboardManager: ClipboardManagerWrapper,
    pairingManager: DevicePairingManager,
    networkManager: NetworkManager
) {
    val isMonitoring by clipboardManager.isMonitoring
    val content by clipboardManager.clipboardContent
    val history = clipboardManager.clipboardHistory
    val pairedCount = pairingManager.pairedDevices.size

    var connectionStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.DISCONNECTED) }

// Update connection status
    LaunchedEffect(Unit) {
        networkManager.onConnectionStatusChanged = { status ->
            connectionStatus = status
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionStatus) {
                    is ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                    is ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = MaterialTheme.shapes.small,
                    color = when (connectionStatus) {
                        is ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                        is ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }

                ) {}

                Text(
                    connectionStatus.displayString(),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Clipboard Sync",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "Auto-syncing to $pairedCount device(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Control
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (isMonitoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ) {}

                Text(
                    if (isMonitoring) "Monitoring Active" else "Not Monitoring",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (isMonitoring) {
                            clipboardManager.stopMonitoring()
                        } else {
                            clipboardManager.startMonitoring()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isMonitoring) "Stop" else "Start")
                }
            }
        }

        // Current Content
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Current Content",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                TextField(
                    value = content,
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        // History
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "History (${history.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (history.isEmpty()) {
                    Text(
                        "No clipboard history yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(16.dp)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(history.size) { index ->
                            val item = history[index]
                            Text(
                                item.content.take(80) + if (item.content.length > 80) "..." else "",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    pairingManager: DevicePairingManager,
    networkManager: NetworkManager
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val devices = pairingManager.pairedDevices
    val deviceId by pairingManager.currentDeviceId
    var manualCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var macIp by remember { mutableStateOf(loadMacIp(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connection Settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Mac Connection",
                    style = MaterialTheme.typography.labelMedium
                )

                TextField(
                    value = macIp,
                    onValueChange = { macIp = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mac IP Address") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            saveMacIp(context, macIp)
                            networkManager.connect(macIp)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = macIp.isNotBlank()
                    ) {
                        Text("Connect")
                    }

                    OutlinedButton(
                        onClick = { networkManager.disconnect() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }

        // Device ID
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Device Pairing",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "Device ID: ${deviceId.take(8)}...",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Manual pairing
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Enter Pairing Code",
                    style = MaterialTheme.typography.labelMedium
                )

                TextField(
                    value = manualCode,
                    onValueChange = { manualCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    placeholder = { Text("device_id|timestamp|key") }
                )

                Button(
                    onClick = {
                        val result = pairingManager.addPairedDevice(manualCode)
                        message = result.second
                        if (result.first) {
                            manualCode = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Device")
                }

                if (message.isNotEmpty()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.contains("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Paired devices
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Paired Devices (${devices.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (devices.isEmpty()) {
                    Text(
                        "No devices paired yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(16.dp)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices.size) { index ->
                            val device = devices[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        device.name,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        "ID: ${device.remoteDeviceId.take(12)}...",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(onClick = { pairingManager.removePairedDevice(device) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun saveMacIp(context: Context, ip: String) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putString("mac_ip", ip)
        .apply()
}

private fun loadMacIp(context: Context): String {
    return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("mac_ip", "") ?: ""
}
