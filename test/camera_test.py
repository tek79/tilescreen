"""Screen camera, as Home Assistant's ESPHome integration drives a camera (aioesphomeapi): the entities by their names
(Camera, Light level, Motion and the plugin's settings), one picture, a stream (asked again for every picture, as Home
Assistant's camera proxy does), pictures in pieces, the settings' coupling (motion waking turns sensing on, sensing off
turns waking off). RenderMain runs with CAMERA=1 and its fake camera (RenderMain.fakeCamera)."""
import asyncio, sys
from aioesphomeapi import APIClient, CameraState

PORT = int(sys.argv[1])


async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    ents, _ = await cli.list_entities_services()
    by = {e.name: e for e in ents}
    for n in ("Camera", "Light level", "Motion", "Camera light level and motion", "Camera motion wakes the screen",
              "Camera upside down", "Camera motion timeout", "Camera field of view", "Camera side"):
        e = by.get(n)
        print(n, "->", type(e).__name__ if e else None, getattr(e, "object_id", ""),
              getattr(e, "device_class", ""), getattr(e, "unit_of_measurement", ""), getattr(e, "options", ""),
              getattr(e, "min_value", ""), getattr(e, "max_value", ""), getattr(e, "step", ""))
    pictures, states = [], {}
    got = asyncio.Event()

    def on_state(s):
        if isinstance(s, CameraState):
            pictures.append(bytes(s.data))
            got.set()
        else:
            states[s.key] = s

    cli.subscribe_states(on_state)
    await asyncio.sleep(0.5)
    name = {e.key: e.name for e in ents}
    for k, s in states.items():
        if name.get(k) in ("Light level", "Motion", "Camera motion timeout", "Camera field of view", "Camera side"):
            print("state", name[k], getattr(s, "state", None), getattr(s, "missing_state", None))

    cli.request_single_image()
    await asyncio.wait_for(got.wait(), 3)
    print("single picture:", len(pictures[0]), "bytes, JPEG" if pictures[0][:2] == b"\xff\xd8" else "not a JPEG")

    # A stream: Home Assistant asks again for each picture.
    t0 = asyncio.get_event_loop().time()
    for i in range(5):
        got.clear()
        cli.request_image_stream()
        await asyncio.wait_for(got.wait(), 3)
    print("stream pictures:", len(pictures) - 1, [len(p) for p in pictures[1:]],
          "in %.1f s" % (asyncio.get_event_loop().time() - t0))

    # The coupling.
    def show(label):
        vals = {name[k]: s.state for k, s in states.items()
                if name.get(k) in ("Camera light level and motion", "Camera motion wakes the screen")}
        print(label, vals)
    cli.switch_command(by["Camera motion wakes the screen"].key, True)
    await asyncio.sleep(0.4)
    show("waking on:")
    cli.switch_command(by["Camera light level and motion"].key, False)
    await asyncio.sleep(0.4)
    show("sensing off:")
    cli.number_command(by["Camera motion timeout"].key, 33)
    cli.select_command(by["Camera field of view"].key, "Sharp")
    cli.select_command(by["Camera side"].key, "Back")
    await asyncio.sleep(0.4)
    for k, s in states.items():
        if name.get(k) in ("Camera motion timeout", "Camera field of view", "Camera side"):
            print("now", name[k], s.state)
    cli.select_command(by["Camera side"].key, "Front")
    cli.select_command(by["Camera field of view"].key, "Full")
    cli.number_command(by["Camera motion timeout"].key, 10)
    await asyncio.sleep(0.3)
    await cli.disconnect()


asyncio.run(main())
