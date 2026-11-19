package com.example.applession.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConnectionStatus(val label: String, val color: Color) {
    DISCONNECTED("Desconectado", Color(0xFFE53935)), // Rojo
    SCANNING("Buscando dispositivos...", Color(0xFFFFB300)), // Amarillo
    CONNECTING("Conectando...", Color(0xFFFFB300)), // Amarillo
    CONNECTED("Conectado", Color(0xFF43A047)) // Verde
}

data class EmgDataPoint(
    val timestampMs: Long,
    val emgValue: Int
)

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val byteBuffer = mutableListOf<Byte>()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _latestEmgData = MutableStateFlow<EmgDataPoint?>(null)
    val latestEmgData: StateFlow<EmgDataPoint?> = _latestEmgData.asStateFlow()

    private val _emgHistory = MutableStateFlow<List<EmgDataPoint>>(emptyList())
    val emgHistory: StateFlow<List<EmgDataPoint>> = _emgHistory.asStateFlow()

    private val _rawStreamLog = MutableStateFlow<String>("")
    val rawStreamLog: StateFlow<String> = _rawStreamLog.asStateFlow()

    // Modo Simulación / Demo
    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private var simulationJob: Job? = null
    private val simulationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun setDemoMode(enabled: Boolean) {
        _isDemoMode.value = enabled
        if (enabled) {
            disconnect() // Desconectar BLE real si estuviera activo
            _connectionStatus.value = ConnectionStatus.CONNECTED
            startSimulation()
            Log.d(TAG, "[DEMO] Modo simulación activado.")
        } else {
            stopSimulation()
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            Log.d(TAG, "[DEMO] Modo simulación desactivado.")
        }
    }

    private fun startSimulation() {
        simulationJob?.cancel()
        _emgHistory.value = emptyList()
        simulationJob = simulationScope.launch {
            var counter = 0L
            val startTime = System.currentTimeMillis()
            while (_isDemoMode.value) {
                val t = (System.currentTimeMillis() - startTime)
                val noise = (150..400).random()
                val contraction = if ((counter / 60) % 3 == 0L && (counter % 60) in 0..25) {
                    (1500..3500).random()
                } else {
                    0
                }
                val emgVal = (noise + contraction).coerceIn(0, 4095)

                val dataPoint = EmgDataPoint(t, emgVal)
                _latestEmgData.value = dataPoint
                _rawStreamLog.value = "$t,$emgVal"

                val currentList = _emgHistory.value.toMutableList()
                currentList.add(dataPoint)
                if (currentList.size > 250) {
                    currentList.removeAt(0)
                }
                _emgHistory.value = currentList

                counter++
                delay(20) // 50 Hz
            }
        }
    }

    private fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        _emgHistory.value = emptyList()
        _latestEmgData.value = null
        _rawStreamLog.value = ""
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "[GATT] Conectado al servidor GATT exitosamente. Iniciando discoverServices()...")
                _connectionStatus.value = ConnectionStatus.CONNECTED
                bluetoothGatt = gatt
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "[GATT] Desconectado del servidor GATT.")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                bluetoothGatt = null
                byteBuffer.clear()
                if (!_isDemoMode.value) {
                    _emgHistory.value = emptyList()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "[GATT] Servicios descubiertos con éxito. Buscando Servicio UART ($UART_SERVICE_UUID)...")
                val service = gatt?.getService(UART_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(TX_CHAR_UUID)
                if (characteristic != null) {
                    Log.d(TAG, "[GATT] Característica TX encontrada ($TX_CHAR_UUID). Configurando notificaciones...")

                    // 1. Habilitar notificaciones en Android
                    val successNotification = gatt.setCharacteristicNotification(characteristic, true)
                    Log.d(TAG, "[GATT] setCharacteristicNotification(true) resultado: $successNotification")

                    // 2. Obtener el descriptor CCCD (0x2902)
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        Log.d(TAG, "[GATT] Descriptor CCCD ($CCCD_UUID) encontrado. Escribiendo ENABLE_NOTIFICATION_VALUE para suscribir al ESP32...")

                        // 3. Escribir valor de habilitación y llamar a writeDescriptor
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                        Log.d(TAG, "[GATT] ¡Suscripción oficial completada! El ESP32 (cccdTX->getNotifications()) iniciará el envío de paquetes.")
                    } else {
                        Log.e(TAG, "[GATT ERROR] Descriptor CCCD (0x2902) NO encontrado en la característica TX.")
                    }
                } else {
                    Log.e(TAG, "[GATT ERROR] Servicio UART o Característica TX no encontrados en el GATT del ESP32.")
                }
            } else {
                Log.w(TAG, "[GATT WARNING] onServicesDiscovered recibido con estado de error: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            processIncomingBytes(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            @Suppress("DEPRECATION")
            characteristic.value?.let { processIncomingBytes(it) }
        }
    }

    private fun processIncomingBytes(bytes: ByteArray) {
        if (_isDemoMode.value) return
        for (b in bytes) {
            if (b == 0x0A.toByte()) { // LF (Line Feed)
                parseLine()
                byteBuffer.clear()
            } else if (b != 0x0D.toByte()) { // Ignore CR
                byteBuffer.add(b)
            }
        }
    }

    private fun parseLine() {
        try {
            val line = byteBuffer.toByteArray().toString(Charsets.UTF_8).trim()
            if (line.isNotEmpty()) {
                _rawStreamLog.value = line
                val parts = line.split(",")
                if (parts.size >= 2) {
                    val timestamp = parts[0].trim().toLong()
                    val emgValue = parts[1].trim().toInt()
                    val dataPoint = EmgDataPoint(timestamp, emgValue)
                    _latestEmgData.value = dataPoint

                    val currentList = _emgHistory.value.toMutableList()
                    currentList.add(dataPoint)
                    if (currentList.size > 250) {
                        currentList.removeAt(0)
                    }
                    _emgHistory.value = currentList

                    Log.d(TAG, "[EMG DATA] Paquete recibido -> t_ms: $timestamp, ADC: $emgValue")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[PARSER ERROR] Error al parsear paquete entrante: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (_isDemoMode.value) return
        _connectionStatus.value = ConnectionStatus.CONNECTING
        Log.d(TAG, "[GATT] Conectando a ${device.name ?: "Desconocido"} (${device.address})...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (_isDemoMode.value) {
            setDemoMode(false)
            return
        }
        Log.d(TAG, "[GATT] Desconectando GATT y cerrando recursos...")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        byteBuffer.clear()
        _emgHistory.value = emptyList()
    }
}
