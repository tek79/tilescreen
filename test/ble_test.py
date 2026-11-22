"""The Bluetooth proxy, as Home Assistant's ESPHome integration drives one (aioesphomeapi, as bleak-esphome calls it):
the device info's features with the switch off and on, the scanner's state, raw advertisements, connections free, and a
connection through the tablet (services, read, write, notifications with the CCCD written by Home Assistant, pairing,
disconnect). RenderMain runs with BLE=1 and its fake radio (RenderMain.fakeBluetooth)."""
import asyncio, sys
from aioesphomeapi import APIClient, BluetoothProxyFeature

PORT = int(sys.argv[1])
DEV = 0xAABBCC000001


async def client():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    return cli


async def main():
    cli = await client()
    info = await cli.device_info()
    print("features with the switch off:", info.bluetooth_proxy_feature_flags, repr(info.bluetooth_mac_address))
    ents, _ = await cli.list_entities_services()
    by = {e.name: e for e in ents}
    print("entities:", [n for n in ("Bluetooth proxy", "Bluetooth status") if n in by])
    cli.switch_command(by["Bluetooth proxy"].key, True)
    await asyncio.sleep(1.0)
    try:
        await cli.disconnect()
    except Exception:
        pass

    cli = await client()
    info = await cli.device_info()
    flags = info.bluetooth_proxy_feature_flags
    print("features with the switch on:", flags, [f.name for f in BluetoothProxyFeature if flags & f], info.bluetooth_mac_address)

    states, free, ads = [], [], []
    cli.subscribe_bluetooth_scanner_state(lambda s: states.append((s.state.name, s.mode.name)))
    cli.subscribe_bluetooth_connections_free(lambda f, l, a: free.append((f, l, list(a))))
    cli.subscribe_bluetooth_le_raw_advertisements(lambda r: ads.extend(r.advertisements))
    await asyncio.sleep(1.2)
    print("scanner:", states[-1] if states else None)
    print("connections free:", free[-1] if free else None)
    print("advertisements:", len(ads), sorted({(hex(a.address), a.address_type) for a in ads}))
    print("  one:", ads[0].rssi, bytes(ads[0].data).hex() if ads else None)

    def state(connected, mtu, error):
        print("  connection:", connected, mtu, error)

    await cli.bluetooth_device_connect(DEV, state, timeout=5, feature_flags=flags, has_cache=False, address_type=0)
    print("free while connected:", free[-1])
    services = await cli.bluetooth_gatt_get_services(DEV)
    for s in services.services:
        print("service", s.uuid, s.handle, [(c.uuid, c.handle, c.properties, [(d.uuid, d.handle) for d in c.descriptors]) for c in s.characteristics])
    print("read 0x12:", bytes(await cli.bluetooth_gatt_read(DEV, 0x12)))
    try:
        await cli.bluetooth_gatt_read(DEV, 0x99)
    except Exception as e:
        print("read 0x99:", type(e).__name__, e)
    await cli.bluetooth_gatt_write(DEV, 0x15, b"hello", True)
    print("write with response: ok")
    await cli.bluetooth_gatt_write(DEV, 0x15, b"x", False)
    got = []
    await cli.bluetooth_gatt_start_notify(DEV, 0x12, lambda h, d: got.append((h, bytes(d))))
    await cli.bluetooth_gatt_write_descriptor(DEV, 0x13, b"\x01\x00")
    print("cccd:", bytes(await cli.bluetooth_gatt_read_descriptor(DEV, 0x13)))
    await asyncio.sleep(2.5)
    print("notifications:", got[:3], len(got))
    print("pair:", await cli.bluetooth_device_pair(DEV))
    await cli.bluetooth_device_disconnect(DEV)
    await asyncio.sleep(0.3)
    print("free after:", free[-1])

    # A device that won't connect: the error comes back.
    try:
        await cli.bluetooth_device_connect(0xAABBCC000002, state, timeout=5, feature_flags=flags, has_cache=False, address_type=1)
    except Exception as e:
        print("other device:", type(e).__name__, e)
    await asyncio.sleep(0.3)
    print("free at the end:", free[-1])
    await cli.disconnect()


asyncio.run(main())
