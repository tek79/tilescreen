"""Template tiles (docs/TEXT_TILE.md): rows as text_tile.block makes them."""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
import page_layout
from page_layout import compile_tiles
_entity = page_layout._entity
def _entity_text(content, *a):
    if content.get('kind') == 'builtin' and content.get('name') == 'text': return 'screen.text'
    return _entity(content, *a)
page_layout._entity = _entity_text
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])
L = lambda b, k=None, hs=None, fx=None: {"state": "on", "attributes": {"brightness": b, "supported_color_modes": ["color_temp", "hs"], "color_mode": "hs" if hs else "color_temp", "min_color_temp_kelvin": 2000, "max_color_temp_kelvin": 6500, "supported_features": 4, "effect_list": ["Rainbow", "Fire"], **({"color_temp_kelvin": k} if k else {}), **({"hs_color": hs} if hs else {}), **({"effect": fx} if fx else {})}}
STATES = {"light.living": L(204, k=2700), "light.desk": L(128, hs=[200.0, 80.0]), "light.strip": L(255, fx="Rainbow", hs=[30.0, 100.0]),
          "light.hall": {"state": "on", "attributes": {"brightness": 51, "supported_color_modes": ["brightness"], "color_mode": "brightness"}}}
P = [{"b": 80, "k": 2700}, {"b": 50, "h": 200, "s": 80}, {"b": 100, "k": 5000}, {"b": 20, "h": 0, "s": 100}]
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
        {"entity": "screen.text", "name": "Chores", "slot": 0, "options": {"size": "square", "template": "x"}},
        {"entity": "screen.text", "name": "Battery", "slot": 2, "options": {"size": "square", "template": "x"}},
        {"entity": "screen.text", "name": "Empty", "slot": 8, "options": {"template": "x"}},
        {"entity": "screen.text", "name": "Broken", "slot": 9, "options": {"size": "wide", "template": "x"}},
        {"entity": "light.hall", "name": "Hall", "slot": 11},
    ]
    record = migrate_legacy({"title": "Templates", "pages": 1, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, STATES) for i, t in enumerate(compiled)]
    # The last hours, as the add-on adds them to a sensor's state (server.py: message['history']).
    import text_tile
    md = [text_tile.block("# Kitchen\n- Empty the **dishwasher**\n- :alert[Rinse aid] is low\n- **Paper** goes out tonight, and this line is long enough that it has to wrap onto a second line on the tile\nAll else is :good[fine]."),
          text_tile.block("## Battery tonight\n| | Planned | Applied |\n|---|---|---|\n| Target | 80 % | 75 % |\n| Price | 0.21 | 0.19 |\nNight power costs **€ 0.21** per kWh.\n:good[On target]"),
          text_tile.block(""), text_tile.error("TemplateSyntaxError: unexpected '}'")]
    for v, m in zip(values, md): v["x"] = {"md": m}
    print("options:", values[0]["x"])
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await cli.disconnect()

asyncio.run(main())
