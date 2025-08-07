package com.example.myapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val receivedMessages = mutableStateListOf<String>()
    private var connectionStatus by mutableStateOf("Disconnected")
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startBluetoothConnection()
        } else {
            receivedMessages.add("Bluetooth permissions denied. Please grant all permissions.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BluetoothTerminalUI(
                messages = receivedMessages,
                connectionStatus = connectionStatus,
                onConnectClick = { checkAndRequestPermissions() },
                onSendCommand = { command -> sendCommandToESP32(command) }
            )
        }

        if (bluetoothAdapter == null) {
            receivedMessages.add("Bluetooth not supported on this device")
        } else if (!bluetoothAdapter.isEnabled) {
            receivedMessages.add("Please enable Bluetooth")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            else -> arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            startBluetoothConnection()
        } else {
            requestBluetoothPermissions.launch(permissionsToRequest)
        }
    }

    private fun startBluetoothConnection() {
        if (bluetoothAdapter?.isEnabled != true) {
            receivedMessages.add("Please enable Bluetooth")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val device: BluetoothDevice? = bluetoothAdapter.bondedDevices.firstOrNull {
                    it.name == "ESP32test" // Ganti dengan nama perangkat ESP32 Anda
                }

                if (device == null) {
                    runOnUiThread { receivedMessages.add("ESP32 not found. Please pair the device first.") }
                    return@launch
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothAdapter.cancelDiscovery()
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                runOnUiThread { connectionStatus = "Connected to ESP32" }

                val inputStream: InputStream = bluetoothSocket!!.inputStream
                val buffer = ByteArray(1024)
                var bytes: Int

                while (true) {
                    try {
                        bytes = inputStream.read(buffer)
                        val incomingMessage = String(buffer, 0, bytes).trim()
                        runOnUiThread { receivedMessages.add("Received: $incomingMessage") }
                    } catch (e: IOException) {
                        runOnUiThread {
                            receivedMessages.add("Connection lost: ${e.message}")
                            connectionStatus = "Disconnected"
                        }
                        break
                    }
                }
            } catch (e: IOException) {
                runOnUiThread {
                    receivedMessages.add("Connection error: ${e.message}")
                    connectionStatus = "Disconnected"
                }
                bluetoothSocket?.close()
            } catch (e: SecurityException) {
                runOnUiThread { receivedMessages.add("Permission error: ${e.message}") }
            }
        }
    }

    private fun sendCommandToESP32(command: String) {
        if (bluetoothSocket?.isConnected != true) {
            receivedMessages.add("Not connected to ESP32")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                runOnUiThread { receivedMessages.add("Sent: $command") }
            } catch (e: IOException) {
                runOnUiThread { receivedMessages.add("Error sending command: ${e.message}") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            receivedMessages.add("Error closing connection: ${e.message}")
        }
    }
}

@Composable
fun BluetoothTerminalUI(
    messages: List<String>,
    connectionStatus: String,
    onConnectClick: () -> Unit,
    onSendCommand: (String) -> Unit
) {
    var commandInput by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ESP32 Bluetooth Terminal",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Status: $connectionStatus",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onConnectClick) {
                    Text("Connect to ESP32")
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Terminal display
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                    reverseLayout = true
                ) {
                    items(messages.reversed()) { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                        )
                    }
                }
                // Command input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        label = { Text("Enter command") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (commandInput.isNotBlank()) {
                                onSendCommand(commandInput)
                                commandInput = ""
                            }
                        }
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}