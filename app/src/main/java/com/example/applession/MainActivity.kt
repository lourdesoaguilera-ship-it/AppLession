package com.example.applession

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.applession.ble.BleManager
import com.example.applession.ble.ConnectionStatus
import com.example.applession.ble.EmgDataPoint
import com.example.applession.ui.theme.AppLessionTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BLEDeviceItem(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    val rssi: Int
)

data class SessionRecord(
    val id: String,
    val dateStr: String,
    val timeStr: String,
    val maxAdc: Int,
    val minAdc: Int,
    val avgAdc: Float
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LessionMainActivity"
    }

    private val targetDeviceName = "ESP32 LESSION"
    private val targetServiceUuid: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var bleManager: BleManager

    private val _discoveredDevices = mutableStateOf<List<BLEDeviceItem>>(emptyList())
    val discoveredDevices: State<List<BLEDeviceItem>> = _discoveredDevices

    private val _isScanning = mutableStateOf(false)
    val isScanning: State<Boolean> = _isScanning

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "[PERMISSIONS] Todos los permisos BLE fueron concedidos.")
            startBleScan()
        } else {
            Log.w(TAG, "[PERMISSIONS] Algunos permisos BLE fueron denegados.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val deviceName = device.name ?: result.scanRecord?.deviceName ?: ""
            val serviceUuids = result.scanRecord?.serviceUuids

            // Filtro estricto y exclusivo del prototipo ESP32 LESSION o su UUID
            val matchesName = deviceName.contains(targetDeviceName, ignoreCase = true)
            val matchesUuid = serviceUuids?.any { it.uuid == targetServiceUuid } == true

            if (matchesName || matchesUuid) {
                Log.d(TAG, "[BLE SCAN] ¡Prototipo detectado! Nombre: '$deviceName' | MAC: '${device.address}'")
                val currentList = _discoveredDevices.value.toMutableList()
                if (currentList.none { it.address == device.address }) {
                    currentList.add(
                        BLEDeviceItem(
                            name = if (deviceName.isNotBlank()) deviceName else targetDeviceName,
                            address = device.address,
                            device = device,
                            rssi = result.rssi
                        )
                    )
                    _discoveredDevices.value = currentList
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "[BLE SCAN] Error en escaneo BLE: código $errorCode")
            _isScanning.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleManager = BleManager(this)

        Log.d(TAG, "[INIT] Aplicación iniciada. Adaptador Bluetooth disponible: ${bluetoothAdapter != null}")

        setContent {
            AppLessionTheme(darkTheme = true) {
                val connectionStatus by bleManager.connectionStatus.collectAsState()
                val latestEmg by bleManager.latestEmgData.collectAsState()
                val emgHistory by bleManager.emgHistory.collectAsState()
                val isDemoMode by bleManager.isDemoMode.collectAsState()

                val sessionHistory = remember { mutableStateListOf<SessionRecord>() }

                // Log de paquetes entrantes y estado de conexión
                LaunchedEffect(latestEmg) {
                    latestEmg?.let {
                        Log.d(TAG, "[DATA STREAM] Paquete EMG recibido -> t_ms: ${it.timestampMs}, ADC: ${it.emgValue}")
                    }
                }

                LaunchedEffect(connectionStatus) {
                    Log.d(TAG, "[GATT STATUS] Estado de conexión actualizado: $connectionStatus")
                }

                LaunchedEffect(connectionStatus, isDemoMode) {
                    if (connectionStatus == ConnectionStatus.DISCONNECTED && !isDemoMode) {
                        if (emgHistory.isNotEmpty()) {
                            val maxAdc = emgHistory.maxOfOrNull { it.emgValue } ?: 0
                            val minAdc = emgHistory.minOfOrNull { it.emgValue } ?: 0
                            val avgAdc = emgHistory.map { it.emgValue }.average().toFloat()
                            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            val now = Date()

                            sessionHistory.add(
                                0,
                                SessionRecord(
                                    id = System.currentTimeMillis().toString(),
                                    dateStr = dateFormat.format(now),
                                    timeStr = timeFormat.format(now),
                                    maxAdc = maxAdc,
                                    minAdc = minAdc,
                                    avgAdc = avgAdc
                                )
                            )
                            Log.d(TAG, "[SESSION SAVED] Sesión finalizada y guardada: Max=$maxAdc, Min=$minAdc, Avg=$avgAdc")
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SingleScreenDashboard(
                        connectionStatus = connectionStatus,
                        isScanning = isScanning.value,
                        devices = discoveredDevices.value,
                        latestEmg = latestEmg,
                        emgHistory = emgHistory,
                        isDemoMode = isDemoMode,
                        sessionHistory = sessionHistory,
                        onToggleDemoMode = { enabled ->
                            Log.d(TAG, "[DEMO] Modo simulación cambiado a: $enabled")
                            bleManager.setDemoMode(enabled)
                        },
                        onStartScan = { checkPermissionsAndScan() },
                        onDeviceClick = { deviceItem ->
                            Log.d(TAG, "[GATT CONNECT] Intentando conectar con: ${deviceItem.name} (${deviceItem.address})")
                            if (isScanning.value) stopBleScan()
                            bleManager.connect(deviceItem.device)
                        },
                        onDisconnect = {
                            Log.d(TAG, "[GATT DISCONNECT] Solicitud de desconexión del usuario.")
                            bleManager.disconnect()
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val hasAll = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (hasAll) {
            startBleScan()
        } else {
            Log.d(TAG, "[PERMISSIONS] Solicitando permisos Bluetooth en tiempo de ejecución...")
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "[BLE SCAN] Bluetooth Le Scanner es nulo. Verifique que Bluetooth esté encendido.")
            return
        }

        if (_isScanning.value) {
            stopBleScan()
            return
        }

        Log.d(TAG, "[BLE SCAN] Iniciando escaneo exclusivo para '$targetDeviceName'...")
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        scanner.startScan(scanCallback)
        window.decorView.postDelayed({
            if (_isScanning.value) {
                Log.d(TAG, "[BLE SCAN] Tiempo límite de escaneo alcanzado (10s). Deteniendo...")
                stopBleScan()
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d(TAG, "[BLE SCAN] Escaneo detenido.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        bleManager.disconnect()
        Log.d(TAG, "[DESTROY] MainActivity destruida.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleScreenDashboard(
    connectionStatus: ConnectionStatus,
    isScanning: Boolean,
    devices: List<BLEDeviceItem>,
    latestEmg: EmgDataPoint?,
    emgHistory: List<EmgDataPoint>,
    isDemoMode: Boolean,
    sessionHistory: List<SessionRecord>,
    onToggleDemoMode: (Boolean) -> Unit,
    onStartScan: () -> Unit,
    onDeviceClick: (BLEDeviceItem) -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = connectionStatus == ConnectionStatus.CONNECTED || isDemoMode
    var showHistorySheet by remember { mutableStateOf(false) }
    var isStatsExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Estadísticas directas en rango ADC (0 - 4095)
    val maxAdc = if (emgHistory.isNotEmpty()) emgHistory.maxOf { it.emgValue } else 0
    val minAdc = if (emgHistory.isNotEmpty()) emgHistory.minOf { it.emgValue } else 0
    val avgAdc = if (emgHistory.isNotEmpty()) emgHistory.map { it.emgValue }.average().toFloat() else 0f

    val currentAdc = latestEmg?.emgValue ?: 0

    // HU-02: Alertas visuales por cambios de color según umbrales ADC
    // Verde: Normal / Relajado (< 1200)
    // Amarillo: Precaución / Contracción moderada (1200 - 3000)
    // Rojo: Riesgo crítico de sobreesfuerzo (>= 3000)
    val (muscleState, alertColor) = when {
        !isConnected -> "Desconectado" to Color(0xFF9CA3AF)
        currentAdc < 1200 -> "Músculo relajado (Normal)" to Color(0xFF00E676) // Verde
        currentAdc < 3000 -> "Músculo en contracción (Precaución)" to Color(0xFFFFB300) // Amarillo
        else -> "¡Riesgo de sobreesfuerzo crítico!" to Color(0xFFFF5252) // Rojo
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Waves,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Lession", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(
                                "Monitoreo EMG (ADC 0-4095)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isDemoMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Switch(
                            checked = isDemoMode,
                            onCheckedChange = onToggleDemoMode,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // HU-01: Tarjeta de Conexión (ESP32)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "ESP32 LESSION",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isConnected) Color(0xFF00E676) else Color(0xFFFF5252)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isConnected) "Conectado al Prototipo" else "Desconectado",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (isConnected) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "78%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isConnected) {
                        Button(
                            onClick = onDisconnect,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Desconectar", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        if (!isDemoMode) {
                            Button(
                                onClick = onStartScan,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = if (isScanning) Icons.Default.Refresh else Icons.Default.Bluetooth,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isScanning) "Deteniendo..." else "Buscar Prototipo (ESP32 LESSION)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // HU-01: Listado de dispositivos exclusivos detectados
            if (!isConnected && !isDemoMode) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dispositivos 'ESP32 LESSION' Encontrados",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isScanning) "Buscando 'ESP32 LESSION'..." else "Presiona 'Buscar Prototipo' para iniciar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices) { device ->
                            DeviceCard(deviceItem = device, onClick = { onDeviceClick(device) })
                        }
                    }
                }
            }

            // HU-02 & HU-03: ESTADO CONECTADO (Valores ADC directos, Alertas de color, Gráfico limpio sin ejes numéricos)
            if (isConnected) {
                Spacer(modifier = Modifier.height(16.dp))

                // HU-02: Fila de Estado Muscular, Valor Actual y Tope de Esfuerzo con Alertas de Color
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Valor ADC Directo actual
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, alertColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Valor ADC Actual",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = currentAdc.toString(),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = alertColor
                            )
                            Text(
                                text = "Rango 0 - 4095",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Right: Tope de Esfuerzo (Máximo) + Alerta Visual de Color
                    Card(
                        modifier = Modifier
                            .weight(1.2f)
                            .border(1.dp, alertColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = alertColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Tope de Esfuerzo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = maxAdc.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = alertColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = muscleState,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = alertColor,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // HU-02: Gráfico de Líneas Limpio (SIN EJES NUMÉRICOS en los costados)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Curva Muscular en Vivo (50 Hz)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(alertColor)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        CleanEmgCanvasChart(
                            emgPoints = emgHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // HU-03: Panel Desplegable de Estadísticas Detalladas (Acordeón)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isStatsExpanded = !isStatsExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Estadísticas Detalladas de Sesión",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(onClick = { isStatsExpanded = !isStatsExpanded }) {
                                Icon(
                                    imageVector = if (isStatsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        AnimatedVisibility(visible = isStatsExpanded) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF2D2640), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    StatColumn(title = "Mínimo ADC", value = minAdc.toString())
                                    VerticalDivider(modifier = Modifier.height(36.dp), color = Color(0xFF2D2640))
                                    StatColumn(title = "Promedio ADC", value = avgAdc.toInt().toString())
                                    VerticalDivider(modifier = Modifier.height(36.dp), color = Color(0xFF2D2640))
                                    StatColumn(title = "Tope ADC", value = maxAdc.toString())
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // HU-03: Botón de Acceso al Historial de Sesiones
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable { showHistorySheet = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Historial de sesiones",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "${sessionHistory.size} sesiones guardadas",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        Text(
                            text = "Ver >",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // ModalBottomSheet para Historial (HU-03)
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Historial de Sesiones EMG",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (sessionHistory.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No hay sesiones guardadas aún.\nConéctate al prototipo o usa el modo Demo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sessionHistory) { session ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${session.dateStr} - ${session.timeStr}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Mín: ${session.minAdc}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Prom: ${session.avgAdc.toInt()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Tope (Máx): ${session.maxAdc}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun CleanEmgCanvasChart(emgPoints: List<EmgDataPoint>, modifier: Modifier = Modifier) {
    val lineColor = Color(0xFF8B5CF6)
    val gradientColor1 = Color(0xFF8B5CF6).copy(alpha = 0.4f)
    val gradientColor2 = Color(0xFF8B5CF6).copy(alpha = 0.0f)

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val paddingBottom = 8.dp.toPx()
            val paddingTop = 8.dp.toPx()
            val chartHeight = height - paddingBottom - paddingTop

            val y0 = paddingTop + chartHeight
            val yMid = paddingTop + chartHeight / 2f
            val yTop = paddingTop

            // Líneas de referencia muy sutiles (SIN NÚMEROS EN LOS EJES)
            drawLine(Color(0xFF261D36), Offset(0f, y0), Offset(width, y0), strokeWidth = 1.dp.toPx())
            drawLine(Color(0xFF261D36), Offset(0f, yMid), Offset(width, yMid), strokeWidth = 1.dp.toPx())
            drawLine(Color(0xFF261D36), Offset(0f, yTop), Offset(width, yTop), strokeWidth = 1.dp.toPx())

            if (emgPoints.size < 2) return@Canvas

            val maxPoints = 150
            val visiblePoints = emgPoints.takeLast(maxPoints)
            val dx = width / (maxPoints - 1).coerceAtLeast(1)

            val path = Path()
            val fillPath = Path()

            visiblePoints.forEachIndexed { index, point ->
                val normalizedValue = (point.emgValue.toFloat() / 4095f).coerceIn(0f, 1f)
                val x = index * dx
                val y = paddingTop + chartHeight * (1f - normalizedValue)

                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, y0)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }

                if (index == visiblePoints.size - 1) {
                    fillPath.lineTo(x, y0)
                }
            }

            // Relleno degradado bajo la curva
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(gradientColor1, gradientColor2),
                    startY = paddingTop,
                    endY = y0
                )
            )

            // Curva principal en violeta neón
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
fun StatColumn(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun DeviceCard(deviceItem: BLEDeviceItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = deviceItem.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "MAC: ${deviceItem.address}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "RSSI: ${deviceItem.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
