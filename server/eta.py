"""Truck progress, collection history and ETA (AGENTS.md sections 6-11).

Words used below:
- stop:  a collection point where the truck parks and nearby residents
         bring their garbage out. Each truck has its own ordered list of
         stops, and that order is its collection order.
- leg:   the drive from one stop to the next.
- run:   one truck's collection round on one day.
- visit: when the truck arrived at a stop during a run, and when it left.
         The gap is the time spent collecting there.

A resident is tied to one truck (they enter its Truck ID) and is served at
that truck's stop nearest to their house.

    ETA = driving time along real roads (OSRM, via routing.py),
          or if the route server is unreachable, distance / average car speed
        + typical time spent collecting at each stop still before the
          resident's, from history (real or demo data)

Road shape of each leg, for the maps: the roads the truck actually drove
there before (cut from a real run's GPS track), else the OSRM road route.
A leg with neither isn't drawn at all.

Every function here expects the caller to hold db.lock.
"""

import datetime
import math
import random
import statistics
import time

import db

# A truck that hasn't reported for this long is treated as offline.
ACTIVE_SECONDS = 120
# Spacing between stops in demo history when no road drive time is known
# yet. Only affects the "usually there around HH:MM" times.
DEMO_SECONDS_BETWEEN_STOPS = 300


def distance_m(lat1, lng1, lat2, lng2):
    """Great-circle distance in metres (haversine). Used for "is the truck at
    this stop" and "which stop is nearest this house", never as a route."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(a))


def today():
    return datetime.date.today().isoformat()


def run_id(truck_id, date=None):
    return f"{truck_id}|{date or today()}"


def is_active(truck):
    return "lat" in truck and time.time() - truck.get("lastSeen", 0) <= ACTIVE_SECONDS


# ---------------------------------------------------------- recording --

def record_position(data, truck_id):
    """Called on every location update (after the point is added to the day's
    track). Marks arrival at any stop the truck is at, and keeps pushing that
    stop's "left" time forward while it stays."""
    truck = data["trucks"][truck_id]
    stops = truck.get("stops", [])

    # Today's run for this truck, created on the first update of the day.
    key = run_id(truck_id)
    if key not in data["runs"]:
        data["runs"][key] = {"truckId": truck_id, "date": today(), "synthetic": False, "visits": {}}
    run = data["runs"][key]

    now = time.time()
    radius = data["settings"]["stopRadiusMeters"]
    for i, stop in enumerate(stops):
        if distance_m(truck["lat"], truck["lng"], stop["lat"], stop["lng"]) > radius:
            continue  # not at this stop

        if stop["id"] not in run["visits"]:
            # First time at this stop today: it just arrived.
            run["visits"][stop["id"]] = {"arrived": now, "left": now}
            if i > 0:
                remember_driven_road(truck_id, truck, run, i, now)

        # Still here, so it hasn't left yet.
        run["visits"][stop["id"]]["left"] = now


def remember_driven_road(truck_id, truck, run, i, arrived):
    """The truck just arrived at stop i. If it came straight from stop i-1
    this run, save the roads it actually took (its GPS points in between) as
    that leg's shape. It replaces the OSRM route on the maps from then on."""
    prev = truck["stops"][i - 1]
    stop = truck["stops"][i]
    prev_visit = run["visits"].get(prev["id"])
    if not prev_visit:
        return
    left = prev_visit["left"]

    # GPS points recorded after leaving the previous stop, before arriving here.
    between = []
    for lat, lng, t in db.read_track(truck_id, today()):
        if left < t < arrived:
            between.append([lat, lng])
    if not between:
        return  # no GPS points in between, so no real road to remember
    prev["driven"] = {
        "toId": stop["id"],
        "from": [prev["lat"], prev["lng"]],
        "to": [stop["lat"], stop["lng"]],
        "points": [[prev["lat"], prev["lng"]]] + between + [[stop["lat"], stop["lng"]]],
        "date": today(),
    }


def progress(data, truck_id):
    """(furthest stop index visited today or -1, index of the stop the truck
    is standing at right now or None)."""
    truck = data["trucks"][truck_id]
    stops = truck.get("stops", [])
    run = data["runs"].get(run_id(truck_id))
    visits = {}
    if run:
        visits = run["visits"]

    last = -1
    for i, stop in enumerate(stops):
        if stop["id"] in visits:
            last = i

    at = None
    if "lat" in truck:
        radius = data["settings"]["stopRadiusMeters"]
        for i, stop in enumerate(stops):
            if distance_m(truck["lat"], truck["lng"], stop["lat"], stop["lng"]) <= radius:
                at = i
                break
    return last, at


# ------------------------------------------------------------ history --

def past_visits(data, truck_id, stop_id):
    """This stop's visits from earlier days (today's may still be in progress)."""
    visits = []
    for run in data["runs"].values():
        if run["truckId"] != truck_id or run["date"] == today():
            continue
        if stop_id in run["visits"]:
            visits.append(run["visits"][stop_id])
    return visits


def typical_dwell(data, truck_id, stop_id):
    """Median seconds spent collecting at a stop, or the default setting."""
    dwells = []
    for visit in past_visits(data, truck_id, stop_id):
        if visit["left"] > visit["arrived"]:
            dwells.append(visit["left"] - visit["arrived"])
    if not dwells:
        return data["settings"]["secondsPerStop"]
    return statistics.median(dwells)


def usual_time(data, truck_id, stop_id):
    """Typical time of day the truck reaches a stop, as "07:40", or None."""
    seconds = []
    for visit in past_visits(data, truck_id, stop_id):
        t = datetime.datetime.fromtimestamp(visit["arrived"])
        seconds.append(t.hour * 3600 + t.minute * 60 + t.second)
    if not seconds:
        return None
    s = int(statistics.median(seconds))
    hours = s // 3600
    minutes = (s % 3600) // 60
    return f"{hours:02d}:{minutes:02d}"


def generate_demo_history(data, truck_id, days=14):
    """Fills in `days` past collection rounds of made-up but consistent data,
    so ETAs have typical per-stop collection times before real history
    exists. Each stop gets its own typical time; each day varies around it.
    Marked synthetic, so clear_demo_history() can remove it later."""
    truck = data["trucks"][truck_id]
    stops = truck.get("stops", [])

    # Each stop's own typical collecting time: 1 to 5 minutes.
    base_dwell = {}
    for stop in stops:
        base_dwell[stop["id"]] = random.uniform(60, 300)

    for days_ago in range(1, days + 1):
        date = datetime.date.today() - datetime.timedelta(days=days_ago)
        # The round starts around 7:00, give or take 10 minutes.
        t = datetime.datetime.combine(date, datetime.time(7, 0)).timestamp()
        t += random.uniform(-600, 600)

        visits = {}
        for i, stop in enumerate(stops):
            if i > 0:
                # Drive from the previous stop: the road's normal time, a bit slower some days.
                leg = cached_leg(truck, i - 1)
                if leg:
                    drive_seconds = leg["seconds"]
                else:
                    drive_seconds = DEMO_SECONDS_BETWEEN_STOPS
                t += drive_seconds * random.uniform(0.9, 1.5)

            dwell = base_dwell[stop["id"]] * random.uniform(0.7, 1.3)
            visits[stop["id"]] = {"arrived": t, "left": t + dwell}
            t += dwell

        data["runs"][run_id(truck_id, date.isoformat())] = {
            "truckId": truck_id, "date": date.isoformat(), "synthetic": True, "visits": visits}
    return days


def clear_demo_history(data, truck_id):
    demo_keys = []
    for key, run in data["runs"].items():
        if run["truckId"] == truck_id and run.get("synthetic"):
            demo_keys.append(key)
    # Deleted after the loop: a dict can't change size while looping over it.
    for key in demo_keys:
        del data["runs"][key]
    return len(demo_keys)


# ------------------------------------------------------ roads & time --

def _stored_leg(truck, i, key):
    """stops[i][key] ("leg" from OSRM or "driven" from GPS), but only if it
    still leads to the current next stop and neither stop has moved since."""
    stops = truck["stops"]
    leg = stops[i].get(key)
    if not leg or i + 1 >= len(stops):
        return None
    a = stops[i]
    b = stops[i + 1]
    if leg["toId"] != b["id"]:
        return None  # the stop order changed
    if leg["from"] != [a["lat"], a["lng"]] or leg["to"] != [b["lat"], b["lng"]]:
        return None  # a stop was moved
    return leg


def cached_leg(truck, i):
    """The OSRM road route from stop i to stop i+1 (routing.py), with its
    drive time."""
    return _stored_leg(truck, i, "leg")


def leg_shape(truck, i):
    """(points, source) of the road from stop i to i+1: "driven" (the roads
    the truck really took), else "osrm", else (None, None)."""
    driven = _stored_leg(truck, i, "driven")
    if driven and driven.get("points"):
        return driven["points"], "driven"
    leg = _stored_leg(truck, i, "leg")
    if leg and leg.get("points"):
        return leg["points"], "osrm"
    return None, None


def nearest_point_index(points, lat, lng):
    """Index of the road point closest to (lat, lng): where the truck is on that road."""
    best = 0
    best_distance = None
    for n, (point_lat, point_lng) in enumerate(points):
        d = distance_m(lat, lng, point_lat, point_lng)
        if best_distance is None or d < best_distance:
            best = n
            best_distance = d
    return best


def road_ahead(points, lat, lng):
    """The part of a road path still ahead of (lat, lng)."""
    return points[nearest_point_index(points, lat, lng):]


def fraction_ahead(points, lat, lng):
    """What fraction (0 to 1) of a road path's length is still ahead of
    (lat, lng). Measured along the road, point to point."""
    nearest = nearest_point_index(points, lat, lng)
    total = 0.0
    ahead = 0.0
    for n in range(len(points) - 1):
        piece = distance_m(points[n][0], points[n][1], points[n + 1][0], points[n + 1][1])
        total += piece
        if n >= nearest:
            ahead += piece
    if total == 0:
        return 0.0
    return ahead / total


def estimate_seconds(settings, lat1, lng1, lat2, lng2):
    """Rough drive time when the route server is unreachable: distance / average car speed."""
    return distance_m(lat1, lng1, lat2, lng2) / (settings["fallbackSpeedKmh"] * 1000 / 3600)


def fresh_approach(data, truck, next_index):
    """The OSRM route from the truck to stop next_index (fetched by
    routing.py), or None if there isn't a recent one with a road shape."""
    approach = truck.get("approach")
    if approach is None or approach["nextIndex"] != next_index:
        return None
    max_age = data["settings"]["routeRefreshMinutes"] * 60 * 2
    if time.time() - approach["computedAt"] >= max_age:
        return None
    first_leg = approach["legs"][0]
    if not first_leg["points"]:
        return None
    return first_leg


def current_leg(data, truck, i):
    """(seconds, road ahead or None, is_rough) for the leg the truck is
    driving right now, towards stop i. Only the part of the road still
    ahead of the truck counts, so the ETA keeps counting down."""
    lat, lng = truck["lat"], truck["lng"]
    stop = truck["stops"][i]
    approach = fresh_approach(data, truck, i)
    stop_to_stop = None
    road = None
    if i > 0:
        stop_to_stop = cached_leg(truck, i - 1)
        road, _ = leg_shape(truck, i - 1)

    # Drive time.
    if approach:
        # Best: the route from exactly where the truck is.
        seconds = approach["seconds"] * fraction_ahead(approach["points"], lat, lng)
        is_rough = False
    elif stop_to_stop and stop_to_stop["points"]:
        # No fresh truck-to-stop route yet (e.g. it just left a stop):
        # use the cached stop-to-stop road, from where the truck is on it.
        seconds = stop_to_stop["seconds"] * fraction_ahead(stop_to_stop["points"], lat, lng)
        is_rough = False
    else:
        seconds = estimate_seconds(data["settings"], lat, lng, stop["lat"], stop["lng"])
        is_rough = True

    # Road shape for the map: as driven / stop-to-stop route, else the approach route.
    if road is None and approach:
        road = approach["points"]
    if road:
        road = road_ahead(road, lat, lng)
    return seconds, road, is_rough


def later_leg(data, truck, i):
    """(seconds, road or None, is_rough) for the leg from stop i-1 to stop i."""
    stops = truck["stops"]
    road, _ = leg_shape(truck, i - 1)
    leg = cached_leg(truck, i - 1)
    if leg:
        return leg["seconds"], road, False
    prev = stops[i - 1]
    seconds = estimate_seconds(data["settings"], prev["lat"], prev["lng"], stops[i]["lat"], stops[i]["lng"])
    return seconds, road, True


def drive(data, truck, next_index, target_index):
    """(seconds, road path, is_rough) from the truck through stops next..target.

    - The leg the truck is on now: see current_leg().
    - Every later leg: that leg's OSRM drive time, cached on the stop.
    - Any leg with neither: distance / average car speed, and is_rough is True.
    The path is [] unless every leg has a real road shape: no straight lines."""
    total_seconds = 0.0
    path = []
    is_rough = False
    missing_road = False

    for i in range(next_index, target_index + 1):
        if i == next_index:
            seconds, road, rough = current_leg(data, truck, i)
        else:
            seconds, road, rough = later_leg(data, truck, i)

        total_seconds += seconds
        if rough:
            is_rough = True
        if road:
            path += road
        else:
            missing_road = True

    if missing_road:
        path = []
    return total_seconds, path, is_rough


# ------------------------------------------------------------- status --

def nearest_stop(truck, lat, lng):
    """(index, metres) of the truck's stop closest to a house, or (None, None)."""
    best = None
    best_distance = None
    for i, stop in enumerate(truck.get("stops", [])):
        d = distance_m(lat, lng, stop["lat"], stop["lng"])
        if best is None or d < best_distance:
            best = i
            best_distance = d
    return best, best_distance


def resident_status(data, resident):
    """Everything a resident needs to know right now. The shape matches
    GET /api/residents/status (docs/EXPLAINER.md)."""
    result = {
        "status": None, "message": None,
        "truckId": resident.get("truckId"), "truckLatitude": None, "truckLongitude": None,
        "stopName": None, "stopLatitude": None, "stopLongitude": None, "stopDistanceMeters": None,
        "etaMinutes": None, "etaIsRough": None, "stopsAway": None, "usualTime": None,
        "path": [], "runId": None,
    }

    truck_id = resident.get("truckId")
    truck = data["trucks"].get(truck_id)
    if truck is None:
        result.update(status="unknown_truck", message=f"No truck with ID {truck_id} is registered.")
        return result

    index, metres = nearest_stop(truck, resident["lat"], resident["lng"])
    if index is None:
        result.update(status="no_stops", message=f"Truck {truck_id} has no collection points set up yet.")
        return result

    stop = truck["stops"][index]
    result.update(
        stopName=stop["name"], stopLatitude=stop["lat"], stopLongitude=stop["lng"],
        stopDistanceMeters=round(metres),
        usualTime=usual_time(data, truck_id, stop["id"]),
        runId=run_id(truck_id),
        truckLatitude=truck.get("lat"), truckLongitude=truck.get("lng"),
    )
    where = f" Your collection point: {stop['name']}, {round(metres)} m from your house."
    usually = ""
    if result["usualTime"]:
        usually = f" Usually there around {result['usualTime']}."

    if not is_active(truck):
        result.update(status="truck_offline",
                      message="The truck isn't sending its location right now." + usually + where)
        return result

    last, at = progress(data, truck_id)

    if at == index:
        result.update(status="at_stop", etaMinutes=0, stopsAway=0,
                      message="The truck is at your collection point now!" + where)
        return result

    if index <= last:
        result.update(status="collected", message="Garbage was already collected at your collection point today." + where)
        return result

    next_index = last + 1
    stops_away = index - next_index
    if stops_away == 0:
        before = "your stop is next"
    elif stops_away == 1:
        before = "1 stop before yours"
    else:
        before = f"{stops_away} stops before yours"

    # ETA = drive time + collecting time at every stop still before this one.
    drive_seconds, points, is_rough = drive(data, truck, next_index, index)
    collecting = 0
    for i in range(next_index, index):
        collecting += typical_dwell(data, truck_id, truck["stops"][i]["id"])
    eta = max(1, math.ceil((drive_seconds + collecting) / 60))

    rough = ""
    if is_rough:
        rough = " Rough estimate: the route server isn't reachable right now."

    path = []
    for lat, lng in points:
        path.append({"latitude": lat, "longitude": lng})

    result.update(
        status="on_the_way",
        message=f"Truck is about {eta} min away ({before}).{rough}{where}",
        etaMinutes=eta,
        etaIsRough=is_rough,
        stopsAway=stops_away,
        path=path,
    )
    return result
