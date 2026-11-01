"""The doorbell (docs/DOORBELL.md): the op, the view, its reports. Pictures in Tessera's new question shape (`q`, docs/PICTURES.md) and live camera tiles pulled one picture an ask.

Every question is read by the add-on's own picture_question.parse; a question with `live` gets a `.mjpeg` link, whose
`.jpg` serves the camera's next picture (a counter drawn in it) as the add-on's LiveFeed.pull does, or 204.
Usage: live.py <port> [command ...]
"""
import asyncio, http.server, io, json, sys, threading, time
from pathlib import Path
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from PIL import Image, ImageDraw, ImageFont
import picture_question, doorbell
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender, caps_of
from render import command

PORT = int(sys.argv[1])
FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 40)
pulls, links = {}, {}

def jpeg(w, h, label, hue):
    im = Image.new("RGB", (w, h), (30 + hue, 60, 110))
    d = ImageDraw.Draw(im)
    d.rectangle([w * 0.1, h * 0.6, w * 0.9, h * 0.9], fill=(50, 90, 50))
    d.text((16, 16), label, font=FONT, fill=(255, 255, 255))
    out = io.BytesIO(); im.save(out, "JPEG", quality=80)
    return out.getvalue()

class Server(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        name = self.path.strip("/")
        token, _, ext = name.rpartition(".")
        link = links.get(token)
        if not link:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        if ext == "jpg" and link["live"]:
            n = pulls.get(token, 0) + 1
            pulls[token] = n
            time.sleep(0.05)
            body = jpeg(link["w"], link["h"], f"{link['label']} #{n}", (n * 7) % 120)
        else:
            body = jpeg(link["w"], link["h"], link["label"] + " still", 60)
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Server)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{server.server_address[1]}"

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    entities, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(m):
        r = await cli.execute_service(msg, {"message": json.dumps(m, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    asks, reports = [], []
    def call(c):
        if c.service == "esphome.screen_camera" and c.is_event: asks.append(dict(c.data))
        if c.service == "esphome.screen_doorbell" and c.is_event: reports.append(dict(c.data)); print("  REPORT", dict(c.data))
    cli.subscribe_service_calls(call)
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Hallway", "pages": 1, "tiles": [
        {"entity": "light.hall", "name": "Hall", "slot": 0},
        {"entity": "camera.garage", "name": "Garage", "slot": 1, "options": {"display": "live", "refresh": 5}},
    ]}, grid)
    ha = {"light.hall": {"state": "on", "attributes": {"brightness": 200}}, "camera.garage": {"state": "idle", "attributes": {}}}
    tiles = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, ha) for i, t in enumerate(tiles)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    bells = doorbell.Doorbells()
    async def aux(m): return await sender.auxiliary(m, session=sender.session, revision=sender.confirmed)
    print("sync:", await sender.synchronize("text.x", record, region, values, [[{"k": "clock"}]]), sender.phase)
    print("doorbell feature:", doorbell.FEATURE in sender.features)

    async def serve(seconds):
        end = time.time() + seconds
        while time.time() < end:
            await asyncio.sleep(0.05)
            while asks:
                req = asks.pop(0)
                q = picture_question.parse(req["q"], int(req["n"]), (1024, 600))
                if q is None:
                    print("  QUESTION NOT READ:", req["q"]); continue
                token = f"p{req['n']}"
                label = q.owner.of.split(".")[1] if q.owner.of else "?"
                links[token] = {"w": q.box[0], "h": q.box[1], "live": bool(q.live), "label": label}
                url = f"{BASE}/{token}." + ("mjpeg" if q.live else "jpg")
                print(f"  question {req['n']}: {q.owner.kind} {label} box {q.box} live {q.live}")
                await sender.auxiliary({**picture_question.answer(q, url), "view": int(req["view"])}, session=req.get("session"), revision=req.get("rev"))

    await command("wait 1", 0.2)
    await serve(2)
    key = bells.start("camera.front_door", "Front door", 12, ["s"], door={"lock": "lock.front"}, keep=30)
    print("ring:", await aux(bells.message(key, door_state="locked")))
    await serve(3)
    await command("render bell-1", 0.3)
    await command("tap 912 170", 0.3); await command("render bell-armed", 0.3)
    await command("tap 912 170", 0.3); await command("render bell-unlocking", 0.3)
    print("lock state:", await aux(bells.door_message(key, "unlocked")))
    await serve(1); await command("render bell-unlocked", 0.3)
    await command("tap 990 36", 0.5)
    await serve(1); await command("render bell-closed", 0.3)
    # A second ring nobody touches: missed after its timeout.
    key2 = bells.start("camera.front_door", "Front door", 10, ["s"], keep=30)
    print("ring 2:", await aux(bells.message(key2)))
    await serve(12)
    await command("render bell-missed", 0.3)
    # The missed ring in the top bar (the harness runs no timers: its close comes from the app).
    await aux(bells.message(key2, close=True)); await serve(0.5)
    bells.unanswered(key2)
    item = bells.bar_item()
    print("bar item:", item)
    record["layout"]["pages"][0]["header"] = {"items": []}
    print("sync 2:", await sender.synchronize("text.x", record, region, values, [[item, {"k": "clock"}]]), sender.phase)
    await serve(1); await command("render bell-bar", 0.3)
    for line in sys.argv[2:]:
        await command(line, 0.3); await serve(2)
    # A ring closed from elsewhere.
    key3 = bells.start("camera.front_door", "Front door", 30, ["s"])
    await aux(bells.message(key3)); await serve(2)
    await aux(bells.message(key3, close=True)); await serve(1)
    await command("render bell-remote-closed", 0.3)
    print("reports:", [(r["action"], r["key"] in (key, key2, key3)) for r in reports])
    print("pulls per link:", pulls)
    await cli.disconnect()

asyncio.run(main())
