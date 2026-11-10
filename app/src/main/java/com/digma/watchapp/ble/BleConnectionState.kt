package com.digma.watchapp.ble

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceAddress: String, val deviceName: String) : BleConnectionState()
    data class Connected(val deviceAddress: String, val deviceName: String) : BleConnectionState()
    data class Syncing(val deviceAddress: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}
