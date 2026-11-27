"""The media card's title, artist and album, its seek bar, shuffle and repeat, from the add-on's extras."""
import asyncio, json, sys, time
from datetime import datetime, timezone
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message, media_extras
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import media_library
from render import command

PORT = int(sys.argv[1])
ATTRS = {"friendly_name": "Living room", "media_title": "Get Lucky", "media_artist": "Daft Punk", "media_album_name": "Random Access Memories",
         "media_duration": 369, "media_position": 95, "media_position_updated_at": datetime.now(timezone.utc).isoformat(),
         "volume_level": 0.4, "shuffle": True, "repeat": "all", "supported_features": 1 | 2 | 4 | 16 | 32 | 512 | 16384 | 32768 | 262144 | 128 | 256}
STATES = {"media_player.living": {"state": "playing", "attributes": ATTRS}}

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
    record = migrate_legacy({"title": "Music", "pages": 1, "tiles": [{"entity": "media_player.living", "name": "Living room", "slot": 0}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    v = state_message(0, compiled[0], STATES)
    v["x"] = {**(media_extras(ATTRS) or {}), **media_library.player_extras(ATTRS)}
    print("x:", v["x"])
    region = {"clock_24h": True, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, [v], [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await asyncio.sleep(0.5)
    print("calls:", calls)
    await cli.disconnect()

asyncio.run(main())
