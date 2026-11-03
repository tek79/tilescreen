"""Plugins (docs/PLUGINS.md): the hello as page_delivery.plugins_of reads it, plugin tiles in Plugins.tile_message's shape,
a plugin's question (esphome.screen_plugin) answered as Plugins.answer does, its bar item, and the flip clock's face.
Usage: plugins_test.py <port> [command ...]"""
import asyncio, json, sys, time
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender, plugins_of
from render import command

PORT = int(sys.argv[1])
NOW = int(time.time())
TOMORROW = NOW + 86400

def x_of(entity, state, attributes):
    return {"state": state, "name": entity.split(".")[1].title(), "attributes": attributes}

def message(i, t):
    o = {k: t["options"][k] for k in ("size",) if k in t["options"]}
    if t["options"].get("plugin"): o["plugin"] = t["options"]["plugin"]
    pe = t["options"].get("plugin_entity", "")
    if pe: o["pe"] = pe
    x = DATA.get(pe)
    m = {"v": 1, "op": "state", "i": i, "entity": t["entity"], "name": t["name"], "state": "ok", "a": {}, "o": o}
    if x: m["x"] = x
    return m

DATA = {
    "climate.hall": x_of("climate.hall", "heat", {"temperature": 21.5, "current_temperature": 19.8, "min_temp": 7, "max_temp": 35,
                                                  "target_temp_step": 0.5, "hvac_action": "heating", "hvac_modes": ["off", "heat", "auto"]}),
    "calendar.waste": x_of("calendar.waste", "off", {"message": "Paper", "start_time": TOMORROW}),
    "media_player.tv": x_of("media_player.tv", "playing", {"is_volume_muted": True}),
    "input_select.activity": x_of("input_select.activity", "Watch TV", {"options": ["Off", "Watch TV", "Blu-ray", "Music", "Games", "Radio"]}),
    "remote.living": x_of("remote.living", "on", {}),
}
TILES = [
    {"entity": "plugin:thermo_dial.dial", "name": "Hall", "slot": 0, "options": {"size": "square", "plugin": {}, "plugin_entity": "climate.hall"}},
    {"entity": "plugin:waste_collection.next", "name": "Bins", "slot": 2, "options": {"plugin": {}, "plugin_entity": "calendar.waste"}},
    {"entity": "plugin:av_wall_panel_controls.transport", "name": "TV", "slot": 3, "options": {"plugin": {
        "play_pause": "media_player.media_play_pause", "previous": "media_player.media_previous_track",
        "next": "media_player.media_next_track", "stop": "media_player.media_stop"}, "plugin_entity": "media_player.tv"}},
    {"entity": "plugin:av_wall_panel_controls.volume", "name": "Volume", "slot": 6, "options": {"plugin": {
        "down": "media_player.volume_down", "mute": "media_player.volume_mute is_volume_muted=true", "up": "media_player.volume_up"},
        "plugin_entity": "media_player.tv"}},
    {"entity": "plugin:av_wall_panel_controls.grid", "name": "Activity", "slot": 8, "options": {"size": "wide", "plugin": {"hide": ["Off"]},
        "plugin_entity": "input_select.activity"}},
    {"entity": "plugin:av_wall_panel_controls.dpad", "name": "Remote", "slot": 7, "options": {"plugin": {
        "up": "remote.send_command command=UP", "down": "remote.send_command command=DOWN", "left": "remote.send_command command=LEFT",
        "right": "remote.send_command command=RIGHT", "ok": "remote.send_command command=OK", "back": "remote.send_command command=BACK",
        "home": "remote.send_command command=HOME"}, "plugin_entity": "remote.living"}},
    {"entity": "plugin:av_wall_panel_controls.numpad", "name": "Channel", "slot": 10, "options": {"size": "wide", "plugin": {
        "action": "remote.send_command command=NUM_{digit}", "send": "each"}, "plugin_entity": "remote.living"}},
]

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(m):
        r = await cli.execute_service(msg, {"message": json.dumps(m, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    events, actions = [], []
    def call(c):
        if c.is_event and c.service == "esphome.screen_plugin": events.append(dict(c.data))
        elif not c.is_event: actions.append((c.service, dict(c.data)))
    cli.subscribe_service_calls(call)
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Plugins", "pages": 1, "tiles": TILES}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [message(i, t) for i, t in enumerate(compiled)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    bar = [[{"k": "plugin", "t": "plugin:waste_collection.soon"}, {"k": "clock"}]]
    print("sync:", await sender.synchronize("text.x", record, region, values, bar), sender.phase)
    print("hello plugins:", "plugins" in sender.features, plugins_of({"plugin_api": sender.plugin_api, "plugins": sender.plugins}))
    async def aux(m): return await sender.auxiliary(m, session=sender.session, revision=sender.confirmed)

    async def answer():
        while events:
            e = events.pop(0)
            body = json.loads(e["body"])
            print("  ASK", e["plugin"], body)
            cal = body["data"]["entity_id"]
            day = lambda d: time.strftime("%Y-%m-%d", time.localtime(NOW + d * 86400))
            result = {cal: {"events": [{"start": day(1), "summary": "Paper"}, {"start": day(4), "summary": "GFT"},
                                       {"start": day(8), "summary": "Plastic"}, {"start": day(11), "summary": "Glass"}]}}
            print("  ANSWER", await aux({"op": "plugin", "p": e["plugin"], "m": {"re": body["re"], "ok": True, "result": result}}))

    for line in sys.argv[2:]:
        if line == "saverop":
            pf = {"p": "plugin:flip_clock.flip", "o": {"seconds": True}, "x": {"temperature": "12°", "items": []}}
            print("saver:", await aux({"op": "saver", "k": "clock", "pf": pf}))
            continue
        await command(line, wait=0.8)
        await answer()
    await asyncio.sleep(0.5)
    for a in actions: print("  HA action:", a)
    await cli.disconnect()

asyncio.run(main())
