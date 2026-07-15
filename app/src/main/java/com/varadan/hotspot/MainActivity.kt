package com.varadan.hotspot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.varadan.hotspot.ui.theme.HotspotTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "All Permissions Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some Permissions Denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.createChannel(this)
        checkAndRequestPermissions()

        setContent {
            HotspotTheme {
                var status by remember { mutableStateOf("Ready") }

                // Using derived states or simply passing context to manager
                val bluetoothSupported = BluetoothManager.isBluetoothSupported(this)
                val bluetoothEnabled = BluetoothManager.isBluetoothEnabled(this)
                val bluetoothPermission = BluetoothManager.hasBluetoothPermission(this)
                val bluetoothName = BluetoothManager.getBluetoothName(this)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        status = status,
                        bluetoothSupported = bluetoothSupported,
                        bluetoothEnabled = bluetoothEnabled,
                        bluetoothPermission = bluetoothPermission,
                        bluetoothName = bluetoothName,
                        onHotspotOn = {
                            NotificationHelper.sendHotspotOn(this)
                            status = "HOTSPOT_ON Notification Sent"
                            Toast.makeText(this, "HOTSPOT_ON", Toast.LENGTH_SHORT).show()
                        },
                        onHotspotOff = {
                            NotificationHelper.sendHotspotOff(this)
                            status = "HOTSPOT_OFF Notification Sent"
                            Toast.makeText(this, "HOTSPOT_OFF", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun HomeScreen(
    status: String,
    bluetoothSupported: Boolean,
    bluetoothEnabled: Boolean,
    bluetoothPermission: Boolean,
    bluetoothName: String,
    onHotspotOn: () -> Unit,
    onHotspotOff: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Smart Hotspot", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Status : $status", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Text("Bluetooth Supported : $bluetoothSupported")
        Text("Bluetooth Enabled : $bluetoothEnabled")
        Text("Bluetooth Permission : $bluetoothPermission")
        Text("Phone Name : $bluetoothName")
        Spacer(modifier = Modifier.height(30.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = onHotspotOn) {
            Text("Turn Hotspot ON")
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = onHotspotOff) {
            Text("Turn Hotspot OFF")
        }
    }
}