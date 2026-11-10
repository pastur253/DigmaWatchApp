# Digma Smartline D3 (Telink TLSR8251) BLE Protocol Specification

## 1. Overview

The **Digma Smartline D3** utilizes a Telink TLSR8251 Bluetooth Low Energy SoC running a customized version of the AdorHealth firmware protocol. Communication occurs over standard GATT attributes using a custom vendor Service.

### Primary GATT Service & Characteristics

| Item | UUID | Description | Properties |
|------|------|-------------|------------|
| **Service** | `0000FFF0-0000-1000-8000-00805F9B34FB` *(or vendor `00010203...`)* | Primary Watch Service | Primary |
| **Write Characteristic (TX)** | `0000FFF1-0000-1000-8000-00805F9B34FB` | Phone -> Watch commands | Write Without Response, Write |
| **Notify Characteristic (RX)** | `0000FFF2-0000-1000-8000-00805F9B34FB` | Watch -> Phone notifications | Notify, Indicate |

---

## 2. Packet Framing

All commands and responses conform to the following byte frame (typical MTU 23 default):

```
+------------+------------+---------------+--------------------+------------+
| Header (1B)| Cmd ID (1B)| Length (1B)   | Payload (0..16 B)  | Checksum(1)|
| 0xAB       | [0x01..FF] | N (data size) | Data bytes         | XOR/Sum    |
+------------+------------+---------------+--------------------+------------+
```

### Checksum Calculation
The 1-byte checksum at the end of the packet is computed across all bytes following the header:
```kotlin
fun calculateChecksum(bytes: ByteArray, offset: Int = 1, length: Int = bytes.size - 2): Byte {
    var chk = 0
    for (i in offset until (offset + length)) {
        chk = chk xor (bytes[i].toInt() and 0xFF)
    }
    return (chk and 0xFF).toByte()
}
```

---

## 3. Command Map (Phone ➔ Watch)

### 3.1. Synchronize Time & Date (`CMD 0x01`)
Sends local smartphone time to update RTC on the watch.

- **Header**: `0xAB`
- **Command**: `0x01`
- **Length**: `0x07`
- **Payload**:
  - `Byte 3`: Year High Byte (`(year >> 8) & 0xFF`)
  - `Byte 4`: Year Low Byte (`year & 0xFF`)
  - `Byte 5`: Month (`1..12`)
  - `Byte 6`: Day of Month (`1..31`)
  - `Byte 7`: Hour (`0..23`)
  - `Byte 8`: Minute (`0..59`)
  - `Byte 9`: Second (`0..59`)
- **Checksum**: `Byte 10`

### 3.2. Query Battery & Firmware Version (`CMD 0x91`)
- **Packet**: `0xAB, 0x91, 0x00, 0x91`
- **Response**: `0xAB, 0x91, 0x03, [Battery%], [FwVerMajor], [FwVerMinor], [Checksum]`

### 3.3. Request Today's Activity / Steps (`CMD 0x51`)
- **Packet**: `0xAB, 0x51, 0x00, 0x51`
- **Response**:
  - `Byte 3..6`: Total steps (32-bit big endian integer)
  - `Byte 7..10`: Total calories burned (kcal / 10)
  - `Byte 11..14`: Total distance covered (meters)

### 3.4. Trigger Heart Rate Measurement (`CMD 0x31`)
- **Start Measurement**: `0xAB, 0x31, 0x01, 0x01, [Checksum]`
- **Stop Measurement**: `0xAB, 0x31, 0x01, 0x00, [Checksum]`
- **Live Stream Response**: `0xAB, 0x31, 0x02, [HeartRateBpm], [SpO2Percent], [Checksum]`

### 3.5. Send Push Notification (`CMD 0x72`)
Sends phone notifications to display on watch screen.

- **Header**: `0xAB`
- **Command**: `0x72`
- **Type**:
  - `0x01`: Incoming Phone Call
  - `0x02`: Missed Call
  - `0x03`: SMS
  - `0x04`: Telegram / WhatsApp / Social Apps
- **Payload**: `[Type (1B)] [Title Length (1B)] [Title UTF-8...] [Body Length (1B)] [Body UTF-8...]`
- *Note*: If the text exceeds MTU or 128 bytes, it is truncated and safely framed.

### 3.6. Find Watch (Vibrate) (`CMD 0x09`)
- **Packet**: `0xAB, 0x09, 0x01, 0x01, 0x08` (triggers motor vibration on D3).

---

## 4. Notifications from Watch (Watch ➔ Phone)

- **Button / Touch Events**:
  - `0xAB, 0xFE, 0x01, 0x01`: Find Phone requested by watch.
  - `0xAB, 0xFE, 0x01, 0x02`: Music Play/Pause toggle.
  - `0xAB, 0xFE, 0x01, 0x03`: Music Next track.
  - `0xAB, 0xFE, 0x01, 0x04`: Music Previous track.
  - `0xAB, 0xFE, 0x01, 0x05`: Remote camera shutter pressed.
