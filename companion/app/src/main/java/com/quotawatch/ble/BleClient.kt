package com.quotawatch.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleClient(private val context: Context) {

    companion object {
        const val TAG = "BleClient"
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        val QUOTA_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    }

    sealed class State {
        data object Disconnected : State()
        data object Scanning : State()
        data object Connecting : State()
        data object Connected : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var quotaCharacteristic: BluetoothGattCharacteristic? = null

    fun scan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            _state.value = State.Error("BLE not available")
            return
        }

        _state.value = State.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        quotaCharacteristic = null
        _state.value = State.Disconnected
    }

    fun sendQuotaData(data: ByteArray): Boolean {
        val char = quotaCharacteristic ?: return false
        val g = gatt ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Found device: ${result.device.name} / ${result.device.address}")
            bluetoothAdapter.bluetoothLeScanner?.stopScan(this)
            _state.value = State.Connecting
            result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = State.Error("Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    this@BleClient.gatt = gatt
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    this@BleClient.gatt = null
                    quotaCharacteristic = null
                    _state.value = State.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                quotaCharacteristic = service?.getCharacteristic(QUOTA_CHAR_UUID)
                if (quotaCharacteristic != null) {
                    _state.value = State.Connected
                    Log.d(TAG, "Connected and ready")
                } else {
                    _state.value = State.Error("Quota characteristic not found")
                }
            } else {
                _state.value = State.Error("Service discovery failed")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: $status")
            }
        }
    }
}
