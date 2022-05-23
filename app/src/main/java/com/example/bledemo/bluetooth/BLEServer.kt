/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.bledemo.bluetooth

import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.net.ConnectivityManager.NetworkCallback
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.bledemo.bluetooth.Message.RemoteMessage
import com.example.bledemo.bluetooth.*
import com.example.bledemo.chat.DeviceConnectionState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.Charset


private const val TAG = "BLEServer"
private const val WIFITAG = "WifiUpdate"

@kotlinx.serialization.Serializable
data class ScanEntry(val n: String, val id: String, val l: Int)

@kotlinx.serialization.Serializable
data class NetworkChoice(val name: String, val id: String, val password: String)

@kotlinx.serialization.Serializable
data class NetworkStatus(val id: String, val status: String)

object BLEServer {
    // hold reference to app context to run the chat server
    private var app: Application? = null

    private lateinit var bluetoothManager: BluetoothManager
    // BluetoothAdapter should never be null if the app is installed from the Play store
    // since BLE is required per the <uses-feature> tag in the AndroidManifest.xml.
    // If the app is installed on an emulator without bluetooth then the app will crash
    // on launch since installing via Android Studio bypasses the <uses-feature> flags
    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    // This property will be null if bluetooth is not enabled or if advertising is not
    // possible on the device
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseSettings: AdvertiseSettings = buildAdvertiseSettings()
    private var advertiseData: AdvertiseData = buildAdvertiseData()
    // LiveData for reporting the messages sent to the device
    private val incomingMessages = MutableLiveData<Message>()
    val messages = incomingMessages as LiveData<Message>
    // LiveData for reporting connection requests
    private val _connectionRequest = MutableLiveData<BluetoothDevice>()
    val connectionRequest = _connectionRequest as LiveData<BluetoothDevice>
    // LiveData for reporting the messages sent to the device
    private val _requestEnableBluetooth = MutableLiveData<Boolean>()
    val requestEnableBluetooth = _requestEnableBluetooth as LiveData<Boolean>
    private var gattServer: BluetoothGattServer? = null
    private var gattServerCallback: BluetoothGattServerCallback? = null
    // Properties for current chat device connection
    private var currentDevice: BluetoothDevice? = null
    private val _deviceConnection = MutableLiveData<DeviceConnectionState>()
    val deviceConnection = _deviceConnection as LiveData<DeviceConnectionState>
    private var networkConfirmationCharacteristic: BluetoothGattCharacteristic? = null
    private var scanResultCharacteristic: BluetoothGattCharacteristic? = null
    // Wifi Variables
    private lateinit var wifiManager: WifiManager
    private var scanResults: List<ScanResult> = listOf()
    private lateinit var connectivityManager: ConnectivityManager
    private val wifiStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "wifiStateReceiver onReceive $intent.action")
            if (intent.action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                scanResults = wifiManager.scanResults
                Log.d(TAG, "Scan results here: $scanResults")
                sendScanResults()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun startServer(app: Application) {
        bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (!adapter.isEnabled) {
            // prompt the user to enable bluetooth
            _requestEnableBluetooth.value = true
        } else {
            _requestEnableBluetooth.value = false
            setupGattServer(app)
            startAdvertisement()
        }
        // Start up Wifi network scanning
        wifiManager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        app.registerReceiver(wifiStateReceiver, intentFilter)
    }

    fun stopServer() {
        stopAdvertising()
    }

    fun setCurrentChatConnection(device: BluetoothDevice) {
        currentDevice = device
        // Set gatt so BluetoothChatFragment can display the device data
        _deviceConnection.value = DeviceConnectionState.Connected(device)
    }

    /**
     * Function to setup a local GATT server.
     * This requires setting up the available services and characteristics that other devices
     * can read and modify.
     */
    private fun setupGattServer(app: Application) {
        BLEServer.app = app
        gattServerCallback = GattServerCallback()

        gattServer = bluetoothManager.openGattServer(
            app,
            gattServerCallback
        ).apply {
            addService(setupGattService())
        }
    }

    /**
     * Function to create the GATT Server with the required characteristics and descriptors
     */
    private fun setupGattService(): BluetoothGattService {
        // Setup gatt service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // need to ensure that the property is writable and has the write permission
        val messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageCharacteristic)
        val confirmCharacteristic = BluetoothGattCharacteristic(
            CONFIRM_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        val descriptor1 = BluetoothGattDescriptor(DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        descriptor1.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        confirmCharacteristic.addDescriptor(descriptor1)
        networkConfirmationCharacteristic = confirmCharacteristic
        service.addCharacteristic(confirmCharacteristic)
        // Setup ScanResult Characteristic
        val src = BluetoothGattCharacteristic(
            READ_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE, // or BluetoothGattCharacteristic.PROPERTY_BROADCAST or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
        )
        val descriptor2 = BluetoothGattDescriptor(DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        descriptor2.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        src.addDescriptor(descriptor2)
        service.addCharacteristic(src)
        scanResultCharacteristic = src

        return service
    }

    /**
     * Add the specified network to the list of suggested networks. A notification can then be
     * presented to the user inviting them to connect to this new network, but the connection
     * must be user driven.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestSelectedNetwork(networkChoice: NetworkChoice): Boolean {
        Log.d(TAG, "requestSelectedNetwork: About to request to network: $networkChoice")
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(networkChoice.name)
            .setWpa2Passphrase(networkChoice.password)
//           .setIsAppInteractionRequired(true) // Optional (Needs location permission)
            .build()
        wifiManager.removeNetworkSuggestions(listOf(suggestion))
        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
        if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            // do error handling here
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
                Log.e(TAG, "WifiManager is attempting to add duplicate network!")
            } else {
                Log.e(TAG, "WifiManager unable to add NetworkSuggestions: $status")
            }
            return false
        }
        Log.d(TAG, "requestSelectedNetwork- Suggestion Successfully Added")
        // Optional (Wait for post connection broadcast to one of your suggestions)
        val intentFilter = IntentFilter(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!intent.action.equals(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)) {
                    return
                }
                // do post connect processing here
                Log.d(TAG, "POST CONNECTION PROCESSING HERE")
            }
        }
        app!!.registerReceiver(broadcastReceiver, intentFilter)

        return true
    }

    /**
     * Directly connect to a network with the specified id and password. This will generate a
     * confirmation dialog that allows the user to Connect or Cancel. If they choose "Cancel", this
     * will invoke the Unavailable callback below.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToSelectedNetwork(networkChoice: NetworkChoice) {
        Log.d(TAG, "connectToSelectedNetwork: About to request to network: $networkChoice")
        val bssid = MacAddress.fromString(networkChoice.id)
        val wifiNetworkSpecifier = WifiNetworkSpecifier.Builder()
            .setBssid(bssid)
            .setWpa2Passphrase(networkChoice.password)
            .build()
//            .setBssidPattern(MacAddress.fromString(networkChoice.id), MacAddress.fromString("ff:ff:ff:00:00:00"))
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(wifiNetworkSpecifier)
            .build()
        val callback = object: NetworkCallback() {
            override fun onAvailable(network : Network) {
                Log.e(WIFITAG, "The default network is now: $network")
                val statusResult = sendNetworkConnectionResult(networkChoice.id, true)
                Log.d(TAG, "sendNetworkConnectionResult returned with result - $statusResult")
            }
            override fun onLost(network : Network) {
                Log.e(WIFITAG, "The application no longer has a default network. The last default network was $network")
            }
            override fun onCapabilitiesChanged(network : Network, networkCapabilities : NetworkCapabilities) {
                Log.e(WIFITAG, "The default network changed capabilities: $networkCapabilities")
            }
            override fun onLinkPropertiesChanged(network : Network, linkProperties : LinkProperties) {
                Log.e(WIFITAG, "The default network changed link properties: $linkProperties")
            }
            override fun onUnavailable() {
                Log.e(WIFITAG, "The requested network was Unavailable")
                val statusResult = sendNetworkConnectionResult(networkChoice.id, false)
                Log.d(TAG, "sendNetworkConnectionResult returned with result - $statusResult")
            }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                Log.e(WIFITAG, "onBlockedStatusChanged to $blocked for network $network")
            }
        }
        connectivityManager.requestNetwork(networkRequest, callback)
        return
    }

    /**
     * Custom callback for the Gatt Server this device implements
     */
    private class GattServerCallback : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            val isSuccess = status == BluetoothGatt.GATT_SUCCESS
            val isConnected = newState == BluetoothProfile.STATE_CONNECTED
            Log.d(
                TAG,
                "onConnectionStateChange: Server $device ${device.name} success: $isSuccess connected: $isConnected"
            )
            if (isSuccess && isConnected) {
                _connectionRequest.postValue(device)
            } else {
                _deviceConnection.postValue(DeviceConnectionState.Disconnected)
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic.uuid == MESSAGE_UUID) {
                val data = value?.toString(Charsets.UTF_8)
                Log.d(TAG, "onCharacteristicWriteRequest decoding message: \"$data\"")
                val message = Json.decodeFromString<NetworkChoice>(data as String)
                incomingMessages.postValue(RemoteMessage(message.toString()))
                // Attempt to connect to the specified Network
                connectToSelectedNetwork(message)
//              requestSelectedNetwork(message))
                // Send acknowledgement of message successfully received
                val resultMessage = "response message".toByteArray(Charsets.UTF_8)
                val res = gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, resultMessage)
                Log.d(TAG, "onCharacteristicWriteRequest sendResponse result $res")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d(TAG, "onDescriptorWriteRequest fired - sending response")
            // now tell the connected device that this was all successful
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            sendScanResults()
        }
    }

    // Sending Messages to TV
    // --------------------------------------------------------------------------------
    /**
     * Sends a subset of the list of scanned Wifi networks to TV
     */
    fun sendScanResults(): Boolean {
        // Try to send scanResultCharacteristic Update
        if (currentDevice != null && scanResultCharacteristic != null) {
            var message: List<ScanEntry> = listOf()
            for (item in scanResults) {
                if (message.count() >= 5) {
                    break
                }
                message = message + ScanEntry(item.SSID, item.BSSID, WifiManager.calculateSignalLevel(item.level, 10))
            }
            val data = Json.encodeToString(message).toByteArray(Charset.defaultCharset())
            scanResultCharacteristic?.value = data
            val notifyResult = gattServer?.notifyCharacteristicChanged(currentDevice, scanResultCharacteristic, false)
            val st = message.toString()
            Log.d(TAG, "Result of sendScanResults sending $st - $notifyResult")
            return true
        }
        return false
    }

    /**
     * Send the success/fail result of attempting to connect to the specified Wifi network to TV
     */
    fun sendNetworkConnectionResult(id: String, status: Boolean): Boolean {
        if (currentDevice != null && networkConfirmationCharacteristic != null) {
            val message = NetworkStatus(id, if (status) "success" else "fail")
            val data = Json.encodeToString(message).toByteArray(Charset.defaultCharset())
            networkConfirmationCharacteristic?.value = data
            val notifyResult = gattServer?.notifyCharacteristicChanged(currentDevice, networkConfirmationCharacteristic, false)
            val st = message.toString()
            Log.d(TAG, "Result of sendNetworkConnectionResult sending $st - $notifyResult")
            return true
        }
        return false
    }

//    fun sendMessage(message: String): Boolean {
//        Log.d(TAG, "Send a message")
//        messageCharacteristic?.let { characteristic ->
//            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
//
//            val messageBytes = message.toByteArray(Charsets.UTF_8)
//            characteristic.value = messageBytes
//            gatt?.let {
//                val success = it.writeCharacteristic(messageCharacteristic)
//                Log.d(TAG, "onServicesDiscovered: message send: $success")
//                if (success) {
//                    incomingMessages.value = Message.LocalMessage(message)
//                }
//            } ?: run {
//                Log.d(TAG, "sendMessage: no gatt connection to send a message with")
//            }
//        }
//        return false
//    }

    // Advertisement Functionality
    // --------------------------------------------------------------------------------
    /**
     * Start advertising this device so other BLE devices can see it and connect
     */
    private fun startAdvertisement() {
        advertiser = adapter.bluetoothLeAdvertiser
        Log.d(TAG, "startAdvertisement: with advertiser $advertiser")

        if (advertiseCallback == null) {
            advertiseCallback = DeviceAdvertiseCallback()

            advertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        }
    }

    /**
     * Stops BLE Advertising.
     */
    private fun stopAdvertising() {
        Log.d(TAG, "Stopping Advertising with advertiser $advertiser")
        advertiser?.stopAdvertising(advertiseCallback)
        advertiseCallback = null
    }

    /**
     * Returns an AdvertiseData object which includes the Service UUID and Device Name.
     */
    private fun buildAdvertiseData(): AdvertiseData {
        /**
         * Note: There is a strict limit of 31 Bytes on packets sent over BLE Advertisements.
         * This limit is outlined in section 2.3.1.1 of this document:
         * https://inst.eecs.berkeley.edu/~ee290c/sp18/note/BLE_Vol6.pdf
         *
         * This limit includes everything put into AdvertiseData including UUIDs, device info, &
         * arbitrary service or manufacturer data.
         * Attempting to send packets over this limit will result in a failure with error code
         * AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE. Catch this error in the
         * onStartFailure() method of an AdvertiseCallback implementation.
         */
        val dataBuilder = AdvertiseData.Builder()
//            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(true)
            .addManufacturerData(0xFF, "192.168".toByteArray(Charsets.UTF_8))
//            .addManufacturerData(0xFF, "192.168.1.1".toByteArray(Charsets.UTF_8))
//            .addServiceData(ParcelUuid(SERVICE_UUID), "hello".toByteArray(Charsets.UTF_8))

        return dataBuilder.build()
    }

    /**
     * Returns an AdvertiseSettings object set to use low power (to help preserve battery life)
     * and disable the built-in timeout since this code uses its own timeout runnable.
     */
    private fun buildAdvertiseSettings(): AdvertiseSettings {
        return AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTimeout(0)
            .build()
    }

    /**
     * Custom callback after Advertising succeeds or fails to start. Broadcasts the error code
     * in an Intent to be picked up by AdvertiserFragment and stops this Service.
     */
    private class DeviceAdvertiseCallback : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            // Send error state to display
            val errorMessage = "Advertise failed with error: $errorCode"
            Log.d(TAG, "Advertising failed - $errorMessage")
            //_viewState.value = DeviceScanViewState.Error(errorMessage)
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising successfully started")
        }
    }

    /**
     * The questions of how to obtain a device's own MAC address comes up a lot. The answer is
     * you cannot; it would be a security breach. Only system apps can get that permission.
     * Otherwise apps might use that address to fingerprint a device (e.g. for advertising, etc.)
     * A user can find their own MAC address through Settings, but apps cannot find it.
     * This method, which some might be tempted to use, returns a default value,
     * usually 02:00:00:00:00:00
     */
//    fun getYourDeviceAddress(): String = bluetoothManager.adapter.address
}