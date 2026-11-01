"""A robot vacuum's tile and card, with the add-on's own vacuum_card.payload, core.vacuum_extras and vacuum_map.

Usage: vacuums.py <port> [command ...]  - commands go to RenderMain (render, tap, ...); "state <name>" switches the
robot's state (docked, cleaning, paused, error) and sends the tile again; the map is answered by vacuum_map.render.
"""
import asyncio, http.server, json, sys, threading
from pathlib import Path
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from PIL import Image, ImageDraw
import core, vacuum_card, vacuum_map, tile_icons, picture_question
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])
PICS = Path("/tmp/ts-pics"); PICS.mkdir(exist_ok=True)
class Quiet(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=str(PICS), **k)
    def log_message(self, *a): pass
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{server.server_address[1]}"

def raw_map():
    """A map as Roborock's integration draws one: loud rooms on a grid of 4 px cells, walls dark, the rest transparent."""
    cell, W, H = 4, 150, 90
    im = Image.new("RGBA", (W * cell, H * cell), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rooms = [((5, 5, 60, 45), (0x4F, 0x8E, 0xF7)), ((60, 5, 105, 45), (0xF7, 0x6B, 0x4F)), ((105, 5, 145, 60), (0x8E, 0xF7, 0x4F)),
             ((5, 45, 50, 85), (0xF7, 0xD1, 0x4F)), ((50, 45, 105, 85), (0xC1, 0x4F, 0xF7))]
    for (x0, y0, x1, y1), colour in rooms:
        d.rectangle([x0 * cell, y0 * cell, x1 * cell - 1, y1 * cell - 1], fill=colour + (255,))
    for (x0, y0, x1, y1), _ in rooms:
        d.rectangle([x0 * cell, y0 * cell, x1 * cell - 1, y1 * cell - 1], outline=(0x40, 0x40, 0x48, 255), width=cell * 2)
    d.ellipse([30 * cell, 25 * cell, 34 * cell, 29 * cell], fill=(255, 255, 255, 255))  # the robot
    import io
    out = io.BytesIO(); im.save(out, "PNG")
    return out.getvalue()
DRAWING = vacuum_map.read(raw_map())

DEVICE = [{"entity_id": e, "translation_key": k} for e, k in [
    ("image.robo_map", "map"), ("select.robo_cleaning_mode", "cleaning_mode"), ("select.robo_mop_intensity", "mop_intensity"),
    ("sensor.robo_battery", None), ("sensor.robo_current_room", "current_room"), ("sensor.robo_vacuum_error", "vacuum_error"),
    ("sensor.robo_cleaning_progress", "cleaning_progress"), ("sensor.robo_cleaning_area", "cleaning_area"),
    ("button.robo_after_dinner", None), ("button.robo_kitchen_and_hall", None), ("button.robo_reset_filter", None)]]
DEVICE[-1]["entity_category"] = "config"
AREAS = [{"area_id": a, "name": n, "icon": i, "floor_id": "ground"} for a, n, i in [
    ("kitchen", "Kitchen", "mdi:silverware-fork-knife"), ("living_room", "Living room", "mdi:sofa"), ("hallway", "Hallway", None),
    ("office", "Office", "mdi:desk"), ("bedroom", "Bedroom", "mdi:bed")]]
FLOORS = [{"floor_id": "ground", "name": "Ground floor"}]
ENTRIES = {"vacuum.robo": {"options": {"vacuum": {"area_mapping": {a["area_id"]: [str(i)] for i, a in enumerate(AREAS)}}}}}
FEATURES = 4 | 8 | 16 | 32 | 512 | 2048 | 4096 | 8192 | 16384

def states(kind):
    s = {"vacuum.robo": {"state": kind, "attributes": {"supported_features": FEATURES, "fan_speed": "balanced",
                                                       "fan_speed_list": ["off", "quiet", "balanced", "turbo", "max"]}},
         "vacuum.plain": {"state": "docked" if kind != "cleaning" else "returning", "attributes": {"supported_features": 4 | 16 | 8192, "battery_level": 64}},
         "image.robo_map": {"state": "2026-10-27T18:00:00", "attributes": {}},
         "select.robo_cleaning_mode": {"state": "vac_and_mop", "attributes": {"options": ["vacuum", "vac_and_mop", "mop", "smart_mode"]}},
         "select.robo_mop_intensity": {"state": "medium", "attributes": {"options": ["off", "low", "medium", "high"]}},
         "sensor.robo_battery": {"state": "92" if kind != "cleaning" else "71", "attributes": {"device_class": "battery"}},
         "sensor.robo_current_room": {"state": "Living room", "attributes": {}},
         "sensor.robo_vacuum_error": {"state": "main_brush_jammed" if kind == "error" else "none", "attributes": {}},
         "sensor.robo_cleaning_progress": {"state": "42", "attributes": {}},
         "sensor.robo_cleaning_area": {"state": "23.4", "attributes": {}},
         "button.robo_after_dinner": {"state": "unknown", "attributes": {"friendly_name": "After dinner"}},
         "button.robo_kitchen_and_hall": {"state": "unknown", "attributes": {"friendly_name": "Kitchen and hall"}},
         "button.robo_reset_filter": {"state": "unknown", "attributes": {"friendly_name": "Reset filter"}}}
    return s

def message(i, tile, st):
    extra = None
    if tile["entity"] == "vacuum.robo":
        extra = core.vacuum_extras(tile, st, DEVICE) or {}
        vc = vacuum_card.payload(tile["entity"], st, DEVICE, ENTRIES, AREAS, FLOORS, None, "Robo",
                                 lambda icon: tile_icons.GLYPHS.get(icon[4:]) if icon.startswith("mdi:") else None)
        if vc: extra["vc"] = vc
    elif tile["entity"] == "vacuum.plain":
        extra = core.vacuum_extras(tile, st, []) or None
    return state_message(i, tile, st, extra)

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(m):
        r = await cli.execute_service(msg, {"message": json.dumps(m, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    asks, calls = [], []
    def on_call(c):
        if c.service == "esphome.screen_camera" and c.is_event: asks.append(dict(c.data))
        elif not c.is_event: calls.append(c)
    cli.subscribe_service_calls(on_call)
    grid = Grid(4, 3)
    tiles = [{"entity": "vacuum.robo", "name": "Robo", "slot": 0, "options": {"size": "square", "controls": "map"}},
             {"entity": "vacuum.robo", "name": "Robo", "slot": 2, "options": {"size": "square", "controls": "rooms"}},
             {"entity": "vacuum.robo", "name": "Robo", "slot": 12, "options": {"size": "3x2", "controls": "map_rooms"}},
             {"entity": "vacuum.robo", "name": "Robo", "slot": 15, "options": {"size": "tall", "controls": "routines"}},
             {"entity": "vacuum.robo", "name": "Robo", "slot": 8, "options": {"size": "wide", "controls": "map"}}]
    record = migrate_legacy({"title": "Vacuums", "pages": 2, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    st = states("docked")
    values = [message(i, t, st) for i, t in enumerate(compiled)]
    print("robo x:", json.dumps(values[0].get("x"))[:600])
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]] * 2), sender.phase)

    async def serve():
        while True:
            await asyncio.sleep(0.05)
            while asks:
                req = asks.pop(0)
                if "q" not in req: print("  old ask", req); continue
                q = picture_question.parse(req["q"], int(req["n"]), (1024, 600))
                if q is None: print("  NOT READ", req["q"]); continue
                (w, h) = q.box
                bg = req["q"]
                ground = json.loads(req["q"])["bg"][0]
                name = f"vt{req['n']}.bmp"
                (PICS / name).write_bytes(vacuum_map.render(DRAWING, (w, h), int(ground, 16), q.dark))
                print("  question", req["n"], q.owner.kind, q.owner.index, [s.text for s in q.sources] if hasattr(q, "sources") else "", (w, h), ground)
                reply = {**picture_question.answer(q, f"{BASE}/{name}"), "view": int(req["view"])}
                print("    answered:", await sender.auxiliary(reply, session=req.get("session"), revision=req.get("rev")))
    serving = asyncio.ensure_future(serve())
    for line in sys.argv[2:]:
        if line.startswith("state "):
            st = states(line.split()[1])
            values = [message(i, t, st) for i, t in enumerate(compiled)]
            print("  resync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]] * 2))
            await asyncio.sleep(0.5)
            continue
        await command(line, wait=1.0)
    await asyncio.sleep(0.5)
    for c in calls: print("  HA call:", c.service, dict(c.data), dict(c.data_template))
    serving.cancel()
    await cli.disconnect()

asyncio.run(main())
