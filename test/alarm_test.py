"""The alarm panel's card and keypad, and a lock's keypad (docs/ALARM_PANEL.md)."""
import asyncio, json, sys, time
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])
A = lambda s, **x: {"state": s, "attributes": {"supported_features": 1 | 2 | 4 | 32, "code_format": "number", "code_arm_required": False,
                                               "friendly_name": "House"}, **x}
STATES = {"alarm_control_panel.house": A("armed_away"), "alarm_control_panel.shed": A("disarmed"),
          "lock.front": {"state": "locked", "attributes": {"code_format": "^\\d{4}$", "supported_features": 1}}}
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
        {"entity": "alarm_control_panel.house", "name": "House", "slot": 0, "options": {"send_after": "4"}},
        {"entity": "alarm_control_panel.shed", "name": "Shed", "slot": 1, "options": {}},
        {"entity": "lock.front", "name": "Front door", "slot": 2, "options": {}},
    ]
    record = migrate_legacy({"title": "Alarm", "pages": 1, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    # The alarm's choice from the Events page, as alarm_screens.with_options adds it after the layout is compiled.
    compiled[0]["options"] = {**(compiled[0].get("options") or {}), "send_after": "4"}
    values = [state_message(i, t, STATES) for i, t in enumerate(compiled)]

    print("o:", [v.get("o") for v in values])
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        if line.startswith("state "):
            _, i, s = line.split(" ", 2)
            i = int(i)
            ent = compiled[i]["entity"]
            STATES[ent] = A(s) if ent.startswith("alarm") else STATES[ent]
            values = [state_message(j, t, STATES) for j, t in enumerate(compiled)]
            if s == "pending":
                values[i].setdefault("x", {})["ae"] = int(time.time()) + 25
            print("state:", s, await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]))
            await asyncio.sleep(0.8)
            continue
        await command(line, wait=1.0)
    await cli.disconnect()

asyncio.run(main())
