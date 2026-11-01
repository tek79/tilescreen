"""Pictures in Tessera's new question shape (`q`, docs/PICTURES.md) and live camera tiles pulled one picture an ask.

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
import picture_question
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
    asks = []
    cli.subscribe_service_calls(lambda c: asks.append(dict(c.data)) if c.service == "esphome.screen_camera" and c.is_event else None)
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Cameras", "pages": 1, "tiles": [
        {"entity": "camera.front", "name": "Front", "slot": 0, "options": {"size": "square", "display": "live", "refresh": 0}},
        {"entity": "camera.back", "name": "Back door", "slot": 2, "options": {"size": "square", "display": "live", "refresh": 0}},
        {"entity": "camera.shed", "name": "Shed", "slot": 8, "options": {"display": "live", "refresh": 0}},
        {"entity": "camera.garage", "name": "Garage", "slot": 9, "options": {"display": "live", "refresh": 5}},
    ]}, grid)
    ha = {e: {"state": "idle", "attributes": {}} for e in ("camera.front", "camera.back", "camera.shed", "camera.garage")}
    tiles = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, ha) for i, t in enumerate(tiles)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.x", record, region, values, [[{"k": "clock"}]]), sender.phase)
    print("features:", "pictures" in sender.features, "live" in sender.features, "caps:", caps_of({"caps": sender.caps, "features": sender.features}) if isinstance(getattr(sender, "caps", None), dict) and "live_tiles" not in sender.caps else sender.caps)
    shown = {}

    async def serve(seconds):
        end = time.time() + seconds
        while time.time() < end:
            await asyncio.sleep(0.05)
            while asks:
                req = asks.pop(0)
                if "q" not in req:
                    print("  an old-shape request:", {k: v for k, v in req.items() if k in ("entity", "tiles")}); continue
                q = picture_question.parse(req["q"], int(req["n"]), (1024, 600))
                if q is None:
                    print("  QUESTION NOT READ:", req["q"]); continue
                token = f"p{req['n']}"
                label = q.owner.of.split(".")[1] if q.owner.of else "?"
                links[token] = {"w": q.box[0], "h": q.box[1], "live": bool(q.live), "label": label}
                url = f"{BASE}/{token}." + ("mjpeg" if q.live else "jpg")
                shown.setdefault(label, set()).add("live" if q.live else "still")
                print(f"  question {req['n']}: {q.owner.kind} {q.owner.index} {label} box {q.box} fit {q.places[0].fit} fmt {q.formats} pace {q.pace} live {q.live}")
                answer = picture_question.answer(q, url)
                print("    answered:", await sender.auxiliary({**answer, "view": int(req["view"])}, session=req.get("session"), revision=req.get("rev")))

    await command("wait 1", 0.2)
    await serve(4)
    await command("render live-1", 0.3)
    await serve(2)
    await command("render live-2", 0.3)
    for line in sys.argv[2:]:
        await command(line, 0.3); await serve(3)
    print("pulls per link:", pulls)
    await cli.disconnect()

asyncio.run(main())
