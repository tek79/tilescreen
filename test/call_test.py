"""Calls between screens (docs/CALLS.md), driven by Tessera's own calls.py: the list, a call out, answered, hung up, and a
call coming in. The tablet's events (esphome.screen_call) are answered as the add-on's Manager.call_report does."""
import asyncio, json, sys, time
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import calls
from render import command

PORT = int(sys.argv[1])
ME = "kitchen-fire"
SCREENS = {ME: {"name": "Kitchen", "hears": True, "speaks": True, "shows": True, "films": False, "online": True, "quiet": False},
           "hall": {"name": "Hall panel", "hears": True, "speaks": True, "shows": True, "films": True, "online": True, "quiet": False},
           "bedroom": {"name": "Bedroom", "hears": True, "speaks": True, "shows": True, "films": False, "online": True, "quiet": True},
           "garage": {"name": "Garage", "hears": True, "speaks": True, "shows": True, "films": False, "online": False, "quiet": False}}

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    events = []
    cli.subscribe_service_calls(lambda c: events.append(dict(c.data)) if c.service == "esphome.screen_call" else None)
    sender = Sender(send)
    grid = Grid(4, 3)
    tiles = [{"entity": "screen.call", "name": "Call", "slot": 0, "options": {}}]
    record = migrate_legacy({"title": "Calls", "pages": 1, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, {}) for i, t in enumerate(compiled)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.x", record, region, values, [[{"k": "clock"}]]), sender.phase)
    print("call in features:", "call" in sender.features)
    book = calls.Calls()
    async def aux(m): return await sender.auxiliary(m, session=sender.session, revision=sender.confirmed)
    async def say(outs):
        for out in outs:
            if isinstance(out, calls.Fire):
                print("  HA event:", out.event, out.data)
                continue
            if out.node != ME:
                print("  (to", out.node + ")", out.state, out.why)
                continue
            m = calls.message(out, name=SCREENS[out.peer]["name"] if out.peer in SCREENS else out.peer)
            if out.state == calls.LIVE:
                m["l"] = "http://127.0.0.1:9/camera/none.mjpeg"; m["tk"] = "http://127.0.0.1:9/camera/none/talk"; m["vs"] = 1; m["ec"] = 0
            print("  op call:", m, await aux(m))
    async def answer_events():
        while events:
            e = events.pop(0)
            print(" event:", e)
            a, now = e["action"], time.monotonic()
            if a == "list":
                for part in calls.contacts_messages(book.contacts(ME, SCREENS)):
                    print("  contacts:", await aux(part))
            elif a == "dial":
                await say(book.dial(ME, e["to"], e["video"] == "1", now, reachable=SCREENS[e["to"]]["online"], quiet=SCREENS[e["to"]]["quiet"]))
            elif a == "answer":
                await say(book.answer(e["key"], ME, now))
            elif a == "decline":
                await say(book.decline(e["key"], ME, now))
            elif a in ("cancel", "end"):
                await say(book.hang_up(e["key"], ME, now))
    for line in sys.argv[2:]:
        if line == "peer-answers":
            call = book.of(ME)
            await say(book.answer(call.key, call.callee, time.monotonic()))
        elif line == "incoming":
            await say(book.dial("hall", ME, True, time.monotonic()))
        elif line.startswith("wait"):
            await asyncio.sleep(float(line.split()[1]))
        else:
            await command(line, wait=0.9)
        await asyncio.sleep(0.3)
        await answer_events()
    await cli.disconnect()

asyncio.run(main())
