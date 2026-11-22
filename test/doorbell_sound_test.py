"""The doorbell's Sound key: the ring's link (`l`, as the add-on's doorbell_link makes it, with the camera's sound for a
screen with a speaker) opens with the view, its room asked (?room=WxH), pictures and sound parts as live_stream.py writes
them; Sound starts on (off for a quiet ring, `m`), a tap turns it off and on, and the stream closes with the view."""
import asyncio, http.server, io, json, sys, threading, time
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from PIL import Image, ImageDraw
import doorbell, live_stream
from core import Grid
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])
asked = []

def jpeg(n):
    im = Image.new("RGB", (640, 360), (40 + n % 100, 70, 120))
    ImageDraw.Draw(im).text((20, 20), f"door #{n}", fill=(255, 255, 255))
    out = io.BytesIO(); im.save(out, "JPEG"); return out.getvalue()

class Server(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        asked.append(self.path)
        self.send_response(200); self.send_header("Content-Type", "multipart/x-mixed-replace;boundary=frame"); self.end_headers()
        try:
            for n in range(400):
                if n % 5 == 0:
                    pic = jpeg(n); self.wfile.write(live_stream.part_head(b"image/jpeg", len(pic)) + pic + b"\r\n")
                pcm = bytes(640)
                self.wfile.write(live_stream.part_head(b"audio/x-s16le;rate=16000", len(pcm)) + pcm + b"\r\n")
                self.wfile.flush(); time.sleep(0.02)
        except Exception:
            pass

http_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Server)
threading.Thread(target=http_server.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{http_server.server_address[1]}"

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    cli.subscribe_service_calls(lambda c: None)
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Door", "pages": 1, "tiles": [{"entity": "screen.clock", "name": "Clock", "slot": 0}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [{"v": 1, "op": "state", "i": i, "entity": t["entity"], "name": t["name"], "state": "", "a": {}} for i, t in enumerate(compiled)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    await sender.synchronize("text.x", record, region, values, [[{"k": "clock"}]])
    async def aux(m): return await sender.auxiliary(m, session=sender.session, revision=sender.confirmed)
    bells = doorbell.Doorbells()
    key = bells.start("camera.front_door", "Front door", 30, ["s"], door={"lock": "lock.front"})
    print("ring:", await aux(bells.message(key, door_state="locked", link=f"{BASE}/camera/tok1.mjpeg")))
    await asyncio.sleep(2.0)
    await command("render sound-1", 0.3)
    print("asked:", asked)
    await command("tap 912 450", 0.5)
    await command("render sound-off", 0.3)
    await asyncio.sleep(1.0)
    await command("tap 912 450", 0.5)
    await asyncio.sleep(1.0)
    await command("tap 990 36", 0.8)
    await asyncio.sleep(0.5)
    # A quiet ring: Sound starts off.
    key2 = bells.start("camera.front_door", "Front door", 30, ["s"], sound=False)
    print("quiet ring:", await aux(bells.message(key2, link=f"{BASE}/camera/tok2.mjpeg")))
    await asyncio.sleep(1.5)
    await command("render sound-quiet", 0.3)
    await command("tap 990 36", 0.8)
    await asyncio.sleep(0.5)
    await cli.disconnect()

asyncio.run(main())
