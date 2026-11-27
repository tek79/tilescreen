"""Weather forecasts with the add-on's own extras (core.extras, week=True as weather_week asks): a wide tile of one row,
a 2x2 tile with its week, a tile whose forecast starts tomorrow (GitHub #280), and the card, Daily and Hourly."""
import asyncio, json, sys
from datetime import datetime, timedelta, timezone
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message, extras
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])
TZ = timezone(timedelta(hours=-6))
now = datetime.now(TZ)
midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
conds = ["sunny", "partlycloudy", "rainy", "cloudy", "pouring", "sunny", "snowy"]
def daily(start):
    return [{"datetime": (midnight + timedelta(days=start + i, hours=12)).isoformat(), "condition": conds[i],
             "temperature": 8 + i * 3 - (i == 4) * 9, "templow": 1 + i, "precipitation_probability": [0, 10, 80, 20, 95, 0, 40][i],
             "precipitation": [0, 0, 6.5, 0.2, 14, 0, 2][i]} for i in range(7)]
hourly = [{"datetime": (midnight + timedelta(hours=now.hour + h)).isoformat(), "condition": "rainy" if 5 < h < 10 else "cloudy",
           "temperature": 6 + 5 * __import__("math").sin((now.hour + h - 9) / 24 * 6.283), "precipitation": 1.2 if 5 < h < 10 else 0} for h in range(48)]
STATE = {"state": "partlycloudy", "attributes": {"temperature": 9.4, "temperature_unit": "°C", "humidity": 71, "wind_speed": 14.4,
         "wind_speed_unit": "km/h", "pressure": 1012, "pressure_unit": "hPa", "apparent_temperature": 7, "friendly_name": "Home", "supported_features": 3}}

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
    record = migrate_legacy({"title": "Weather", "pages": 1, "tiles": [
        {"entity": "weather.home", "name": "Home", "slot": 0, "options": {"size": "wide", "display": "forecast"}},
        {"entity": "weather.home", "name": "Home", "slot": 2, "options": {"size": "wide"}},
        {"entity": "weather.home", "name": "Week", "slot": 4, "options": {"size": "square", "display": "forecast"}},
        {"entity": "weather.later", "name": "Tomorrow on", "slot": 6, "options": {"size": "square", "display": "forecast"}}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    states = {"weather.home": STATE, "weather.later": STATE}
    values = []
    for i, t in enumerate(compiled):
        v = state_message(i, t, states)
        start = 1 if t["entity"] == "weather.later" else 0
        v["x"] = extras(t, states, daily(start), TZ, hourly, now=now, week=True)
        values.append(v)
    print("days:", [d.get("d") + "/" + str(d.get("w")) for d in values[0]["x"]["days"]], "hours:", len(values[0]["x"]["hours"]))
    region = {"clock_24h": True, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await cli.disconnect()

asyncio.run(main())
