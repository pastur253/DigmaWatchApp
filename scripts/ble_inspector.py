#!/usr/bin/env python3
"""
BLE Inspector & Sniffer for Digma Smartline D3 (Telink TLSR8251)
Requires: pip install bleak
"""

import asyncio
import datetime
import sys
try:
    from bleak import BleakScanner, BleakClient
except ImportError:
    BleakScanner = None
    BleakClient = None

TARGET_NAMES = ["Smartline D3", "Smartline", "D3", "AdorHealth", "TLSR8251"]

# Common vendor service & characteristic UUIDs for Telink/AdorHealth/Fit wearables
COMMON_WRITE_UUIDS = [
    "0000fff1-0000-1000-8000-00805f9b34fb",
    "00010203-0405-0607-0809-0a0b0c0d1911",
    "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
]
COMMON_NOTIFY_UUIDS = [
    "0000fff2-0000-1000-8000-00805f9b34fb",
    "00010203-0405-0607-0809-0a0b0c0d1912",
    "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
]


def calculate_checksum(data: bytearray) -> int:
    """Calculates standard 1-byte XOR or sum checksum across packet payload."""
    chk = 0
    for b in data:
        chk ^= b
    return chk & 0xFF


def build_time_sync_packet() -> bytes:
    """
    Builds standard AdorHealth/Telink time synchronization packet:
    Header: 0xAB, Command: 0x01, Length: 0x07, Data: [YearH, YearL, Month, Day, Hour, Min, Sec], CRC
    """
    now = datetime.datetime.now()
    pkt = bytearray([
        0xAB,                  # Header
        0x01,                  # CMD_SYNC_TIME
        0x07,                  # Length
        (now.year >> 8) & 0xFF,
        now.year & 0xFF,
        now.month,
        now.day,
        now.hour,
        now.minute,
        now.second
    ])
    pkt.append(calculate_checksum(pkt[1:]))
    return bytes(pkt)


def build_find_watch_packet() -> bytes:
    """Sends find watch (vibrate) packet."""
    pkt = bytearray([0xAB, 0x09, 0x01, 0x01])
    pkt.append(calculate_checksum(pkt[1:]))
    return bytes(pkt)


def notification_callback(sender, data: bytearray):
    hex_str = " ".join(f"{b:02X}" for b in data)
    ascii_str = "".join(chr(b) if 32 <= b <= 126 else "." for b in data)
    timestamp = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"[{timestamp}] [NOTIFY from {sender}]")
    print(f"   HEX:   {hex_str}")
    print(f"   ASCII: {ascii_str}\n")


async def scan_devices():
    print("=== Scanning for BLE devices (10 seconds)... ===")
    devices = await BleakScanner.discover(timeout=10.0, return_adv=True)
    found_target = None

    for d, adv in devices.values():
        name = d.name or adv.local_name or "Unknown"
        is_target = any(t.lower() in name.lower() for t in TARGET_NAMES)
        marker = "🎯 [TARGET FOUND]" if is_target else "  "
        print(f"{marker} {d.address} | Name: {name:<20} | RSSI: {adv.rssi} dBm")
        if is_target and not found_target:
            found_target = d.address

    return found_target


async def inspect_device(address: str):
    print(f"\n=== Connecting to {address}... ===")
    async with BleakClient(address) as client:
        if not client.is_connected:
            print("Failed to connect.")
            return

        print(f"Connected! MTU: {client.mtu_size}")
        print("\n=== Discovered Services and Characteristics ===")

        write_char = None
        notify_char = None

        for service in client.services:
            print(f"\n[Service] {service.uuid} ({service.description})")
            for char in service.characteristics:
                props = ",".join(char.properties)
                print(f"  └── [Char] {char.uuid} ({props}) - {char.description}")
                for desc in char.descriptors:
                    print(f"        └── [Desc] {desc.uuid} ({desc.description})")

                if "notify" in char.properties or "indicate" in char.properties:
                    if not notify_char or char.uuid.lower() in COMMON_NOTIFY_UUIDS:
                        notify_char = char.uuid

                if "write-without-response" in char.properties or "write" in char.properties:
                    if not write_char or char.uuid.lower() in COMMON_WRITE_UUIDS:
                        write_char = char.uuid

        print("\n==============================================")
        print(f"Identified Primary Write Char : {write_char}")
        print(f"Identified Primary Notify Char: {notify_char}")
        print("==============================================")

        if notify_char:
            print(f"Subscribing to notifications on {notify_char}...")
            await client.start_notify(notify_char, notification_callback)

        if write_char:
            print("Sending test Time Sync packet...")
            sync_pkt = build_time_sync_packet()
            print(f"TX Time Packet: {' '.join(f'{b:02X}' for b in sync_pkt)}")
            await client.write_gatt_char(write_char, sync_pkt, response=False)
            await asyncio.sleep(2.0)

            print("Sending test Find Watch packet...")
            find_pkt = build_find_watch_packet()
            print(f"TX Find Packet: {' '.join(f'{b:02X}' for b in find_pkt)}")
            await client.write_gatt_char(write_char, find_pkt, response=False)

        print("\nListening for incoming watch notifications for 20 seconds. Press Ctrl+C to stop...")
        try:
            await asyncio.sleep(20.0)
        except asyncio.CancelledError:
            pass

        if notify_char:
            await client.stop_notify(notify_char)
        print("Disconnected cleanly.")


async def main():
    target_address = sys.argv[1] if len(sys.argv) > 1 else None
    if not target_address:
        target_address = await scan_devices()

    if target_address:
        await inspect_device(target_address)
    else:
        print("\nNo Digma Smartline D3 automatically matched. Run with specific MAC/UUID:")
        print("python scripts/ble_inspector.py XX:XX:XX:XX:XX:XX")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nAborted by user.")
