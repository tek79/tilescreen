"""Locks, automations and what runs (Tessera 0.4.104): a lock's second tap, an automation's tap and hold, and the
last-run lines of a script, a scene and a button from the add-on's extras (core.extras)."""
import asyncio, json, sys, time
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message, extras
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])
now = time.time()
from datetime import datetime, timezone
iso = lambda t: datetime.fromtimestamp(t, timezone.utc).isoformat()
STATES = {
    "lock.front": {"state": "locked", "attributes": {"friendly_name": "Front door", "supported_features": 1}},
    "lock.shed": {"state": "locked", "attributes": {"friendly_name": "Shed"}},
    "automation.porch": {"state": "on", "attributes": {"friendly_name": "Porch light", "last_triggered": iso(now - 3600)}},
    "script.bed": {"state": "off", "attributes": {"friendly_name": "Bedtime", "last_triggered": iso(now - 86400 - 600)}},
    "scene.movie": {"state": iso(now - 300), "attributes": {"friendly_name": "Movie"}},
    "button.never": {"state": "unknown", "attributes": {"friendly_name": "Doorbell test"}},
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
    record = migrate_legacy({"title": "Runs", "pages": 1, "tiles": [
        {"entity": "lock.front", "name": "Front door", "slot": 0},
        {"entity": "lock.shed", "name": "Shed", "slot": 1, "options": {"guard": "lock_only"}},
        {"entity": "automation.porch", "name": "Porch light", "slot": 2},
        {"entity": "automation.porch", "name": "Run porch", "slot": 3, "options": {"tap": "run"}},
        {"entity": "script.bed", "name": "Bedtime", "slot": 4},
        {"entity": "scene.movie", "name": "Movie", "slot": 5},
        {"entity": "button.never", "name": "Test", "slot": 6}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = []
    for i, t in enumerate(compiled):
        v = state_message(i, t, STATES)
        x = extras(t, STATES)
        if x: v["x"] = x
        values.append(v)
    print("extras:", [v.get("x") for v in values])
    region = {"clock_24h": True, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await asyncio.sleep(0.5)
    print("calls:", calls)
    await cli.disconnect()

asyncio.run(main())
