"""The whole "database": one JSON file, loaded into memory at startup and
rewritten after every change. Raw GPS tracks are the one exception: they
grow all day, so they're appended to per-day files under data/tracks/
instead (see append_track).

The server handles requests on several threads, so anything that reads or
changes `data` must hold `lock`:

    with db.lock:
        db.data["trucks"][truck_id] = {...}
        db.save()
"""

import json
import os
import threading

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
DB_FILE = os.path.join(DATA_DIR, "db.json")
TRACKS_DIR = os.path.join(DATA_DIR, "tracks")

DEFAULT_SETTINGS = {
    # A truck within this distance of a stop is "at" that stop.
    "stopRadiusMeters": 50,
    # Time spent collecting at a stop, used until that stop has history.
    "secondsPerStop": 120,
    # How often to re-ask the route server for the road from each moving
    # truck to its next stop.
    "routeRefreshMinutes": 2,
    # Average car speed for the rough ETA used when the route server is unreachable.
    "fallbackSpeedKmh": 20,
    # Optional country code (e.g. "in") to narrow location search.
    "searchCountryCodes": "",
}

lock = threading.RLock()


def _load():
    data = {
        "trucks": {},
        "residents": {},
        "runs": {},
        "alerts": {},
        "settings": {},
    }
    try:
        with open(DB_FILE, encoding="utf-8") as f:
            data.update(json.load(f))
    except FileNotFoundError:
        pass
    # A corrupt file raises here on purpose, rather than silently starting
    # empty and overwriting it on the next save.

    # Start from the defaults, then apply whatever the admin has changed.
    settings = dict(DEFAULT_SETTINGS)
    settings.update(data["settings"])
    data["settings"] = settings
    return data


data = _load()


def save():
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = DB_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    # Replace in one step so a crash mid-write can't leave half a file.
    os.replace(tmp, DB_FILE)


def track_file(truck_id, date):
    # Truck IDs are typed by people, so keep only safe characters for a
    # folder name (no "../" tricks).
    safe_id = ""
    for ch in truck_id:
        if ch.isalnum() or ch in "-_":
            safe_id += ch
        else:
            safe_id += "_"
    return os.path.join(TRACKS_DIR, safe_id, f"{date}.jsonl")


def append_track(truck_id, date, lat, lng, t):
    """Records the roads a truck actually drove: one [lat, lng, time] line
    per location update, one file per truck per day."""
    path = track_file(truck_id, date)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        # Full-precision time: it's compared against stop arrival/departure
        # times to cut out the road driven between two stops.
        f.write(json.dumps([lat, lng, t]) + "\n")


def read_track(truck_id, date):
    points = []
    try:
        with open(track_file(truck_id, date), encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    points.append(json.loads(line))
    except FileNotFoundError:
        pass  # no updates from this truck that day
    return points
