"""Sensor meters (ring, tape, bar) at several sizes, with the add-on's own options and state messages."""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])
S = lambda v, u, dc=None: {"state": v, "attributes": {"unit_of_measurement": u, **({"device_class": dc} if dc else {})}}
STATES = {"sensor.humidity": S("47", "%", "humidity"), "sensor.co2": S("920", "ppm", "carbon_dioxide"),
          "sensor.power": S("1740", "W", "power"), "sensor.battery": S("18", "%", "battery"), "sensor.temp": S("71.4", "°F", "temperature")}

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    grid = Grid(4, 3)
    tiles = [
        {"entity": "sensor.humidity", "name": "Humidity", "slot": 0, "options": {"display": "meter"}},
        {"entity": "sensor.co2", "name": "Office CO2", "slot": 1, "options": {"display": "meter", "meter": "ring", "min": 400, "max": 2000, "segments": 3, "thresholds": [1000, 1500]}},
        {"entity": "sensor.power", "name": "House power", "slot": 2, "options": {"display": "meter", "meter": "tape", "min": 0, "max": 5000, "size": "wide"}},
        {"entity": "sensor.battery", "name": "Phone", "slot": 4, "options": {"display": "meter", "meter": "bar", "segments": 2, "colors": ["red", "green"], "thresholds": [25]}},
        {"entity": "sensor.temp", "name": "Living room", "slot": 5, "options": {"display": "meter", "meter": "ring", "min": 50, "max": 90, "size": "wide"}},
        {"entity": "sensor.co2", "name": "Office CO2", "slot": 7, "options": {"display": "meter", "meter": "bar", "min": 400, "max": 2000, "segments": 3, "size": "tall"}},
        {"entity": "sensor.power", "name": "House power", "slot": 8, "options": {"display": "meter", "meter": "bar", "min": 0, "max": 5000, "size": "wide"}},
        {"entity": "sensor.humidity", "name": "Humidity", "slot": 10, "options": {"display": "meter", "meter": "tape"}},
    ]
    record = migrate_legacy({"title": "Meters", "pages": 1, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, STATES) for i, t in enumerate(compiled)]
    # The last hours, as the add-on adds them to a sensor's state (server.py: message['history']).
    import math
    for v in values:
        if v["entity"] == "sensor.temp":
            v["history"] = {"hours": 24, "values": [round(68 + 3 * math.sin(i / 4), 1) for i in range(24)]}
    print("options:", [v.get("o") for v in values][:3])
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await cli.disconnect()

asyncio.run(main())
