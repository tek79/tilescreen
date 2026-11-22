"""The wake word, end to end. Home Assistant's side is aioesphomeapi as its ESPHome integration uses it: the satellite's
wake word configuration (available, active, Home Assistant choosing one), the Wake word switch and Wake phrase select,
and a question that the wake word starts (VoiceAssistantRequest with wake_word_phrase, the microphone's audio after it).

RenderMain runs with VOICE=1 WAKE=<16 kHz s16le file of someone saying a phrase>; see RenderMain.wake.

Before that, the numbers: test/WakeMain.java against the C microfrontend (pymicro_features' sources, kissfft v130) and
TensorFlow Lite Micro's own interpreter (pip tflite-micro):

    java app.tilescreen.core.WakeMain features <raw>             == the C's ref program, every value
    java app.tilescreen.core.WakeMain probs <model> <features>   == tflite_micro runtime, every output
"""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from aioesphomeapi.model import VoiceAssistantEventType as E
from core import Grid
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])


async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    ents, services = await cli.list_entities_services()
    by_name = {e.name: e for e in ents}
    print("entities:", [n for n in ("Wake word", "Wake phrase", "Wake sound") if n in by_name])
    print("phrase options:", by_name["Wake phrase"].options)
    states = {}
    cli.subscribe_states(lambda s: states.__setitem__(s.key, s))
    conf = await cli.get_voice_assistant_configuration(5)
    print("configuration:", [(w.id, w.wake_word, w.trained_languages) for w in conf.available_wake_words],
          conf.active_wake_words, conf.max_active_wake_words)

    msg = next(s for s in services if s.name == "screen_message")

    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)

    started, audio = [], [0]

    async def handle_start(conversation_id, flags, audio_settings, wake_word_phrase):
        started.append((flags, wake_word_phrase))
        print("  start: flags", flags, "phrase", repr(wake_word_phrase))
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
    values = [{"v": 1, "op": "state", "i": i, "entity": t["entity"], "name": t["name"], "state": "ok", "a": {},
               "o": {"size": "wide", "plugin": {}}} for i, t in enumerate(compiled)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    await sender.synchronize("text.x", record, region, values, [[{"k": "clock"}]])
    await asyncio.sleep(0.5)

    def show(name):
        e = by_name[name]
        s = states.get(e.key)
        return getattr(s, "state", None)

    print("wake word at first:", show("Wake word"), "/", show("Wake phrase"))
    await command("render 01-off.png", wait=0.8)

    # 1. Wake word off: the phrase plays, nothing starts.
    await asyncio.sleep(4)
    print("started while off:", len(started))

    # 2. On, from Home Assistant: the phrase starts a question with the phrase, the same recording goes up.
    cli.switch_command(by_name["Wake word"].key, True)
    await asyncio.sleep(0.6)
    await command("render 02-on.png", wait=0.8)
    for _ in range(80):
        if started:
            break
        await asyncio.sleep(0.1)
    print("started after on:", started[-1:] if started else None)
    await asyncio.sleep(1.5)
    print("audio after the phrase:", audio[0] > 0)
    await command("render 03-listening.png", wait=0.8)
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_STT_VAD_END, None)
    await asyncio.sleep(0.3)
    cli.send_voice_assistant_event(E.VOICE_ASSISTANT_RUN_END, None)
    await asyncio.sleep(0.6)

    # 3. Home Assistant chooses another wake word: Wake phrase follows; the Okay Nabu clip no longer starts anything.
    before = len(started)
    await cli.set_voice_assistant_configuration(["hey_jarvis"])
    await asyncio.sleep(0.6)
    print("phrase after Home Assistant chose:", show("Wake phrase"))
    conf = await cli.get_voice_assistant_configuration(5)
    print("active now:", conf.active_wake_words)
    await command("render 04-jarvis.png", wait=0.8)
    await asyncio.sleep(6)
    print("started with another phrase:", len(started) - before)

    # 4. Back to Okay Nabu with the select, then off: it rests.
    cli.select_command(by_name["Wake phrase"].key, "Okay Nabu")
    await asyncio.sleep(0.5)
    print("phrase after select:", show("Wake phrase"))
    cli.switch_command(by_name["Wake word"].key, False)
    await asyncio.sleep(0.5)
    print("wake word after off:", show("Wake word"))
    await command("settings", wait=0.8)
    await command("render 05-settings.png", wait=0.8)
    await cli.disconnect()


asyncio.run(main())
