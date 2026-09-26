"""Drives a pretend truck through its stops by sending the same location
updates the driver app sends. Lets you watch ETAs count down, stops get
visited and alerts fire, without a real truck. Run it while server.py is
running:

    python simulate.py TRUCK-1          # real pace: update every 10 s, ~15 km/h
    python simulate.py TRUCK-1 --fast   # same path, one update per second

The truck needs stops (admin panel, or the driver app's "Add stop" button).
Between stops it follows the known road (the OSRM route, or one driven
before). With no road known for a leg, it jumps straight to the next stop
rather than inventing a path.

--fast still records real visit history, just squeezed into minutes. Use
a test truck for it, or real ETAs will learn that the route takes minutes.
"""

import json
import sys
import time
import urllib.request

import db
import eta

# 127.0.0.1, not "localhost": on Windows "localhost" tries IPv6 first and
# stalls ~2 s per request before falling back to IPv4.
SERVER = "http://127.0.0.1:8080"
STEP_METRES = 40   # distance moved per update (~15 km/h at one update per 10 s)
DWELL_UPDATES = 3  # updates spent parked at each stop, "collecting"


def send(truck_id, lat, lng):
    body = {
        "truckId": truck_id,
        "latitude": lat,
        "longitude": lng,
        "timestamp": int(time.time() * 1000),
    }
    request = urllib.request.Request(
        f"{SERVER}/api/trucks/location",
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    urllib.request.urlopen(request, timeout=5).close()


def path_points(truck, i):
    """Points to drive through to reach stop i: the known road shape if any,
    ending at the stop itself."""
    points = []
    if i > 0:
        shape, _ = eta.leg_shape(truck, i - 1)
        if shape:
            points = list(shape)  # a copy, so the stored road isn't changed
    stop = truck["stops"][i]
    points.append([stop["lat"], stop["lng"]])
    return points


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    truck_id = sys.argv[1]
    interval = 1 if "--fast" in sys.argv else 10

    truck = db.data["trucks"].get(truck_id)
    if not truck or not truck.get("stops"):
        sys.exit(f"{truck_id} needs stops first (admin panel, or the driver app's Add stop button).")

    stops = truck["stops"]
    print(f"Driving {truck_id} through {len(stops)} stops, one update every {interval}s.", flush=True)

    lat = stops[0]["lat"]
    lng = stops[0]["lng"]
    for i, stop in enumerate(stops):
        points = path_points(truck, i)
        if len(points) == 1:
            # No known road: jump there.
            lat = stop["lat"]
            lng = stop["lng"]

        for n in range(len(points)):
            target_lat, target_lng = points[n]
            distance = eta.distance_m(lat, lng, target_lat, target_lng)
            is_last_point = n == len(points) - 1
            if distance < STEP_METRES and not is_last_point:
                continue  # road points closer than one step: skip ahead

            # Move toward the point in STEP_METRES steps.
            steps = max(1, int(distance // STEP_METRES))
            start_lat = lat
            start_lng = lng
            for step in range(1, steps + 1):
                lat = start_lat + (target_lat - start_lat) * step / steps
                lng = start_lng + (target_lng - start_lng) * step / steps
                send(truck_id, lat, lng)
                time.sleep(interval)
        # Park there a little, like a truck collecting.
        print(f"  at stop {i + 1}: {stop['name']}", flush=True)
        for _ in range(DWELL_UPDATES):
            send(truck_id, lat, lng)
            time.sleep(interval)

    print("Round finished.", flush=True)


if __name__ == "__main__":
    main()
