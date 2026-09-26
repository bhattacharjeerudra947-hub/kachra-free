"""Background thread that gets driving routes from OSRM (osm.py), the free
OpenStreetMap routing service (AGENTS.md sections 8, 10).

Two jobs:

1. Road between consecutive stops, for legs the truck hasn't actually
   driven yet (once it has, eta.py uses the roads it really took). Asked
   once, and again only when stops are added or moved. Cached on each stop
   as "leg", with that road's normal drive time.

2. Road from each moving truck to its next stop ("approach"), since the
   truck is usually somewhere between two stops. Refreshed every
   routeRefreshMinutes, and as soon as the truck reaches a new stop. One
   request per truck, shared by all of that truck's residents.

If OSRM can't be reached, ETAs fall back to a rough distance-based estimate
(eta.drive) and the admin panel's Alerts panel says so. Requests happen
outside db.lock, so they never hold up the server.
"""

import time

import db
import eta
import osm

# Stops per request: the public server handles far more, but small batches
# stay well within its fair-use limits.
STATIC_CHUNK = 12
RETRY_AFTER_ERROR_SECONDS = 120

_last_error_at = {}  # truck_id -> time of its last failed request, to back off


def static_jobs(truck_id, truck):
    """Route requests for this truck's legs that have no road shape yet
    (neither driven by the truck nor fetched already). Each request covers
    up to STATIC_CHUNK stops in a row, from the first leg that's missing."""
    stops = truck.get("stops", [])

    first_missing = None
    for i in range(len(stops) - 1):
        shape, _ = eta.leg_shape(truck, i)
        if shape is None:
            first_missing = i
            break
    if first_missing is None:
        return []

    jobs = []
    start = first_missing
    while start < len(stops) - 1:
        # Copies, so the request can run without holding db.lock.
        chunk = []
        for stop in stops[start:start + STATIC_CHUNK]:
            chunk.append(dict(stop))
        jobs.append((truck_id, start, chunk))
        # The next chunk starts at this chunk's last stop, so no leg is skipped.
        start += STATIC_CHUNK - 1
    return jobs


def approach_job(data, truck_id, truck):
    """(truck_id, next_index, points) if this truck's route ahead is due for
    a refresh, else None."""
    stops = truck.get("stops", [])
    if not stops or not eta.is_active(truck):
        return None
    last, _ = eta.progress(data, truck_id)
    next_index = last + 1
    if next_index >= len(stops):
        return None  # round finished

    approach = truck.get("approach")
    refresh_after = data["settings"]["routeRefreshMinutes"] * 60
    if approach and approach["nextIndex"] == next_index:
        if time.time() - approach["computedAt"] < refresh_after:
            return None  # still fresh

    next_stop = stops[next_index]
    return truck_id, next_index, [(truck["lat"], truck["lng"]), (next_stop["lat"], next_stop["lng"])]


def same_stop(now, before):
    """True if a stop still has the same id and position as our copy of it."""
    return now["id"] == before["id"] and now["lat"] == before["lat"] and now["lng"] == before["lng"]


def run_static(job):
    truck_id, start, chunk = job
    points = []
    for stop in chunk:
        points.append((stop["lat"], stop["lng"]))
    legs = osm.route(points)

    with db.lock:
        truck = db.data["trucks"].get(truck_id)
        if not truck:
            return
        stops = truck["stops"]
        for offset, leg in enumerate(legs):
            a = chunk[offset]
            b = chunk[offset + 1]
            i = start + offset
            # The admin may have edited the stops while we waited for the
            # answer. Only store it if those two stops are still consecutive
            # and unmoved.
            if i + 1 >= len(stops):
                continue
            if not same_stop(stops[i], a) or not same_stop(stops[i + 1], b):
                continue
            stops[i]["leg"] = {
                "toId": b["id"],
                "from": [a["lat"], a["lng"]],
                "to": [b["lat"], b["lng"]],
                "seconds": leg["seconds"],
                "meters": leg["meters"],
                "points": leg["points"],
            }
        db.save()


def run_approach(job):
    truck_id, next_index, points = job
    legs = osm.route(points)
    with db.lock:
        truck = db.data["trucks"].get(truck_id)
        if truck:
            truck["approach"] = {"computedAt": time.time(), "nextIndex": next_index, "legs": legs}
            db.save()


def updater():
    """Runs forever on a background thread."""
    while True:
        time.sleep(10)
        update_once()


def update_once():
    # 1. Under the lock, quickly decide which requests are needed.
    jobs = []  # ("static" or "approach", job)
    with db.lock:
        for truck_id, truck in db.data["trucks"].items():
            if time.time() - _last_error_at.get(truck_id, 0) < RETRY_AFTER_ERROR_SECONDS:
                continue  # failed recently: give the route server a break
            for job in static_jobs(truck_id, truck):
                jobs.append(("static", job))
            approach = approach_job(db.data, truck_id, truck)
            if approach:
                jobs.append(("approach", approach))

    # 2. Without the lock, make the (slow) requests one by one.
    for kind, job in jobs:
        truck_id = job[0]
        try:
            if kind == "static":
                run_static(job)
            else:
                run_approach(job)
            error = None
        except Exception as e:
            _last_error_at[truck_id] = time.time()
            error = f"Route server (OSRM) request failed: {e}"

        # Remember the latest error (or its absence) for the admin panel.
        with db.lock:
            truck = db.data["trucks"].get(truck_id)
            if truck is not None and truck.get("routingError") != error:
                truck["routingError"] = error
                db.save()
