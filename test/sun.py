"""The sun path (display sunpath) and the sun's rise and set line."""
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
from datetime import datetime, timedelta, timezone
TZ = timezone(timedelta(hours=-6))
day = datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0)
SUN = lambda state: {"state": state, "attributes": {"friendly_name": "Sun", "next_rising": (day + timedelta(days=1, hours=6, minutes=52)).isoformat(),
                                                     "next_setting": (day + timedelta(hours=17, minutes=31)).isoformat()}}
STATES = {"sun.sun": SUN("above_horizon")}
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
    record = migrate_legacy({"title": "Lamps", "pages": 1, "tiles": [{"entity": "sun.sun", "name": "Sun", "slot": 0, "options": {"size": "wide", "display": "sunpath"}},
                                                                    {"entity": "sun.sun", "name": "Sun", "slot": 2}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = []
    for i, t in enumerate(compiled):
        v = state_message(i, t, STATES)
        from core import extras
        x = extras(t, STATES, tz=TZ)
        if x: v["x"] = x
        values.append(v)
    region = {"clock_24h": True, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase, "group_lamps:", sender.group_lamps)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await asyncio.sleep(0.5)
    print("calls:", calls)
    await cli.disconnect()

asyncio.run(main())
