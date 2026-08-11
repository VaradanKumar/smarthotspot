package com.varadan.hotspot

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.varadan.hotspot.ui.theme.HotspotTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var hotspotService: BluetoothHotspotService? = null
    private var isBound = false

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager?.adapter }
    private var receiverRegistered = false

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                LogManager.addLog("Bluetooth enabled by user")
            } else {
                LogManager.addLog("Bluetooth enable request denied")
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            (service as? BluetoothHotspotService.LocalBinder)?.let {
                hotspotService = it.getService()
                isBound = true
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hotspotService = null
            isBound = false
        }
    }

    private val gattUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothHotspotService.ACTION_LOG_UPDATE) {
                val message = intent.getStringExtra(BluetoothHotspotService.EXTRA_LOG_MESSAGE)
                if (message != null) LogManager.addLog(message)
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                LogManager.addLog("Permissions granted")
                tryToStartService()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
            }
        }

    private fun requestEnableBluetooth() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(intent)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannel(this)

        setContent {
            HotspotTheme {
                var serverState by remember { mutableStateOf(BluetoothServer.getState()) }
                var isBluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
                var isIgnoringBatteryOptimizations by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    while(true) {
                        serverState = BluetoothServer.getState()
                        isBluetoothEnabled = com.varadan.hotspot.BluetoothManager.isBluetoothEnabled(this@MainActivity)
                        isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(packageName)
                        delay(1000)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        serverState = serverState,
                        isBluetoothEnabled = isBluetoothEnabled,
                        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                        onEnableBluetooth = { requestEnableBluetooth() },
                        onToggleServer = {
                            if (hasAllPermissions()) {
                                tryToStartService()
                            } else {
                                checkAndRequestPermissions()
                            }
                        },
                        onRequestBatteryOptimization = { requestIgnoreBatteryOptimizations() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val gattServiceIntent = Intent(this, BluetoothHotspotService::class.java)
        bindService(gattServiceIntent, serviceConnection, BIND_AUTO_CREATE)
        
        val filter = IntentFilter(BluetoothHotspotService.ACTION_LOG_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gattUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gattUpdateReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStop() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        if (receiverRegistered) {
            unregisterReceiver(gattUpdateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun tryToStartService() {
        val intent = Intent(this, BluetoothHotspotService::class.java)
        if (BluetoothServer.isServerRunning()) {
            intent.action = BluetoothHotspotService.ACTION_STOP_SERVICE
            startForegroundService(intent)
        } else {
            startForegroundService(intent)
        }
    }

    private fun hasAllPermissions(): Boolean {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return list.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkAndRequestPermissions() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (list.isNotEmpty()) requestPermissionLauncher.launch(list.toTypedArray())
    }
}

@Composable
fun HomeScreen(
    serverState: BluetoothServer.ServerState,
    isBluetoothEnabled: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    onEnableBluetooth: () -> Unit,
    onToggleServer: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    val isRunning = serverState != BluetoothServer.ServerState.INACTIVE

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "AirBeam Pro",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        StatusCard(serverState)
        
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = onToggleServer,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isRunning) "Stop Engine" else "Start Engine",
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isBluetoothEnabled) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = onEnableBluetooth,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Enable Bluetooth")
            }
        }

        if (!isIgnoringBatteryOptimizations) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRequestBatteryOptimization) {
                Text("Disable Battery Optimization (Recommended for S22)", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            "Activity Logs",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
        )
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            LazyColumn(modifier = Modifier.padding(12.dp)) {
                items(LogManager.logs.reversed()) { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusCard(state: BluetoothServer.ServerState) {
    val (statusText, color) = when (state) {
        BluetoothServer.ServerState.INACTIVE -> "Stopped" to Color.Red
        BluetoothServer.ServerState.STARTING -> "Starting..." to Color.Yellow
        BluetoothServer.ServerState.GATT_READY -> "Ready" to Color.Cyan
        BluetoothServer.ServerState.ADVERTISING -> "Broadcasting" to Color.Green
        BluetoothServer.ServerState.STOPPING -> "Stopping..." to Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Status: $statusText",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
