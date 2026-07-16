package com.varadan.hotspot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
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

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_CANCELED) {
                Toast.makeText(this, "Device is now discoverable", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.createChannel(this)
        checkAndRequestPermissions()
        
        // Auto-start the service as soon as the app is opened
        val serviceIntent = Intent(this, BluetoothHotspotService::class.java)
        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Auto-start failed: ${e.message}")
        }

        setContent {
            HotspotTheme {
                var isServerRunning by remember { mutableStateOf(BluetoothServer.isServerRunning()) }
                var activePort by remember { mutableStateOf(BluetoothServer.activePort) }
                
                LaunchedEffect(Unit) {
                    while(true) {
                        isServerRunning = BluetoothServer.isServerRunning()
                        activePort = BluetoothServer.activePort
                        kotlinx.coroutines.delay(1000)
                    }
                }
                var status by remember { 
                    mutableStateOf(if (isServerRunning) "Server Running (Background)" else "Ready") 
                }

                val bluetoothSupported = BluetoothManager.isBluetoothSupported(this)
                val bluetoothEnabled = BluetoothManager.isBluetoothEnabled(this)
                val bluetoothName = BluetoothManager.getBluetoothName(this)
                val bluetoothPermission = BluetoothManager.hasBluetoothPermission(this)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        status = status,
                        isServerRunning = isServerRunning,
                        activePort = activePort,
                        bluetoothSupported = bluetoothSupported,
                        bluetoothEnabled = bluetoothEnabled,
                        bluetoothPermission = bluetoothPermission,
                        bluetoothName = bluetoothName,
                        onHotspotOn = {
                            NotificationHelper.sendHotspotOn(this)
                            status = "HOTSPOT_ON Notification Sent"
                        },
                        onHotspotOff = {
                            NotificationHelper.sendHotspotOff(this)
                            status = "HOTSPOT_OFF Notification Sent"
                        },
                        onToggleServer = {
                            val serviceIntent = Intent(this, BluetoothHotspotService::class.java)
                            if (isServerRunning) {
                                stopService(serviceIntent)
                                isServerRunning = false
                                status = "Server Stopped"
                            } else if (!BluetoothManager.isBluetoothEnabled(this)) {
                                status = "Turn Bluetooth ON first"
                                Toast.makeText(this, "Turn Bluetooth ON first", Toast.LENGTH_SHORT).show()
                            } else {
                                startForegroundService(serviceIntent)
                                isServerRunning = true
                                status = "Server Running (Background)"
                            }
                        },
                        onMakeDiscoverable = {
                            if (BluetoothManager.isBluetoothEnabled(this)) {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                                }
                                discoverableLauncher.launch(intent)
                            } else {
                                status = "Turn Bluetooth ON first"
                                Toast.makeText(this, "Turn Bluetooth ON first", Toast.LENGTH_SHORT).show()
                            }
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Already have all permissions, start service
            startHotspotService()
        }
    }

    private fun startHotspotService() {
        val serviceIntent = Intent(this, BluetoothHotspotService::class.java)
        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Service start failed: ${e.message}")
        }
    }
}

@Composable
fun HomeScreen(
    status: String,
    isServerRunning: Boolean,
    activePort: Int,
    bluetoothSupported: Boolean,
    bluetoothEnabled: Boolean,
    bluetoothPermission: Boolean,
    bluetoothName: String,
    onHotspotOn: () -> Unit,
    onHotspotOff: () -> Unit,
    onToggleServer: () -> Unit,
    onMakeDiscoverable: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Smart Hotspot", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = if (isServerRunning) Color.Green else Color.Red,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isServerRunning) "Server Running" + (if (activePort != -1) " (Port $activePort)" else "") else "Server Stopped",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        
        Text(text = "Last Status: $status", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Text("Bluetooth Supported: $bluetoothSupported")
        Text("Bluetooth Enabled: $bluetoothEnabled")
        Text("Bluetooth Permission: $bluetoothPermission")
        Text("Phone Name: $bluetoothName")
        
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = "Tip: Find MAC address in Phone Settings > About > Status",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(modifier = Modifier.fillMaxWidth(), onClick = onToggleServer) {
            Text(if (isServerRunning) "Stop Bluetooth Server" else "Start Bluetooth Server")
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(modifier = Modifier.fillMaxWidth(), onClick = onMakeDiscoverable) {
            Text("Make Device Discoverable")
        }

        Spacer(modifier = Modifier.height(30.dp))
        Text("Manual Controls:", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = onHotspotOn) {
                Text("Hotspot ON")
            }
            Button(modifier = Modifier.weight(1f), onClick = onHotspotOff) {
                Text("Hotspot OFF")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val context = LocalContext.current
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                NotificationHelper.sendHotspotOn(context)
                Toast.makeText(context, "Testing Routine: Sending HOTSPOT_ON", Toast.LENGTH_SHORT).show()
            },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("Step 1: Test Samsung Routine (Manual)")
        }

        Spacer(modifier = Modifier.height(20.dp))
        
        Text("Activity Logs:", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.align(Alignment.Start))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray.copy(alpha = 0.1f))
                .padding(8.dp)
        ) {
            LazyColumn {
                items(LogManager.logs) { log ->
                    Text(
                        text = log,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
