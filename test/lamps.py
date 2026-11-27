"""A light group's lamps page (group_lamps, light_groups.lamps), and a climate card's Fan and Swing rows."""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import light_groups
from render import command

PORT = int(sys.argv[1])
L = lambda name, state, b=None, modes=("brightness",): {"state": state, "attributes": {"friendly_name": name, "supported_color_modes": list(modes), **({"brightness": b} if b else {})}}
STATES = {
    "light.kitchen": {"state": "on", "attributes": {"friendly_name": "Kitchen", "entity_id": ["light.k1", "light.k2", "light.k3", "light.k4"],
                      "supported_color_modes": ["brightness"], "brightness": 180, "supported_features": 0}},
    "light.k1": L("Island", "on", 200), "light.k2": L("Sink", "off"), "light.k3": L("Pantry", "on", None, ("onoff",)), "light.k4": L("Cabinet", "unavailable"),
    "climate.office": {"state": "cool", "attributes": {"friendly_name": "Office", "temperature": 22, "current_temperature": 24, "hvac_modes": ["off", "cool", "heat", "auto"],
                       "fan_modes": ["auto", "low", "medium", "high"], "fan_mode": "auto", "swing_modes": ["off", "vertical", "both"], "swing_mode": "off", "supported_features": 1 | 8 | 32}},
}

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    calls = []
    cli.subscribe_service_calls(lambda c: calls.append((c.service, dict(c.data))))
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Lamps", "pages": 1, "tiles": [{"entity": "light.kitchen", "name": "Kitchen", "slot": 0},
                                                                    {"entity": "climate.office", "name": "Office", "slot": 1}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = []
    for i, t in enumerate(compiled):
        v = state_message(i, t, STATES)
        lamps = light_groups.lamps(t["entity"], STATES)
        if lamps: v.setdefault("x", {})["lamps"] = lamps
        values.append(v)
    region = {"clock_24h": True, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase, "group_lamps:", sender.group_lamps)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await asyncio.sleep(0.5)
    print("calls:", calls)
    await cli.disconnect()

asyncio.run(main())
