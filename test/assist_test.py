"""The voice assistant, push to talk: Home Assistant's side played by aioesphomeapi as its ESPHome integration does
(subscribe, a start answered with port 0 for audio over the API, the pipeline's events), the popup and the tile drawn."""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from aioesphomeapi.model import VoiceAssistantEventType as E
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender, plugins_of
from render import command

PORT = int(sys.argv[1])
async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    info = await cli.device_info()
    print("voice features:", info.voice_assistant_feature_flags)
    ents, services = await cli.list_entities_services()
    print("switches:", [e.name for e in ents if type(e).__name__ == "SwitchInfo" and e.name in ("Wake sound", "Thinking sound", "Show popup")])
    print("configuration:", await cli.get_voice_assistant_configuration(5))
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    started, audio = [], [0]
    async def handle_start(conversation_id, flags, audio_settings, wake_word_phrase):
        started.append((conversation_id, flags, audio_settings))
        return 0
    async def handle_stop(abort):
        print("  stop, abort:", abort)
    async def handle_audio(data, data2=None):
        audio[0] += len(data)
    cli.subscribe_voice_assistant(handle_start=handle_start, handle_stop=handle_stop, handle_audio=handle_audio)
    sender = Sender(send)
    grid = Grid(4, 3)
    tiles = [{"entity": "plugin:voice_assist.assist", "name": "Assistant", "slot": 0, "options": {"size": "wide", "plugin": {}}}]
    record = migrate_legacy({"title": "Assist", "pages": 1, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [{"v": 1, "op": "state", "i": i, "entity": t["entity"], "name": t["name"], "state": "ok", "a": {}, "o": {"size": "wide", "plugin": {}}} for i, t in enumerate(compiled)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.x", record, region, values, [[{"k": "plugin", "t": "plugin:voice_assist.busy"}, {"k": "clock"}]]), sender.phase)
    print("voice_assist in hello:", [p for p in plugins_of({"plugin_api": sender.plugin_api, "plugins": sender.plugins})[1] if p["id"] == "voice_assist"])
    for line in sys.argv[2:]:
        if line.startswith("ev "):
            parts = line.split(" ", 2)
            data = json.loads(parts[2]) if len(parts) > 2 else None
            cli.send_voice_assistant_event(E[parts[1]], data)
            await asyncio.sleep(0.4)
        elif line.startswith("wait"):
            await asyncio.sleep(float(line.split()[1]))
        else:
            await command(line, wait=0.9)
        if started and line.startswith("tap"):
            print("  started:", started[-1])
    print("audio bytes:", audio[0])
    await cli.disconnect()

asyncio.run(main())
