"""Drives a pretend truck through its stops by sending the same requests the
driver app sends: a location every 2 seconds, "Garbage collected" at each
stop, and "stop sharing" at the end. Lets you watch ETAs count down, stops
get collected and alerts fire, without a real truck. Run it while
server.py is running:

    python simulate.py TRUCK-1          # real pace: ~15 km/h
    python simulate.py TRUCK-1 --fast   # same path, 10x faster

The truck needs stops (admin panel, or the driver app's "Add stop" button).
Between stops it follows the known road (the OSRM route, or one driven
before). With no road known for a leg, it jumps straight to the next stop
rather than inventing a path.

It records a real collection round for today, so use a test truck, and
don't use the truck you'll demo with on the same day.
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
UPDATE_SECONDS = 2   # like the driver app
PARK_UPDATES = 10    # updates spent parked at each stop before "collected"


def post(path, body):
    request = urllib.request.Request(
        SERVER + path,
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    urllib.request.urlopen(request, timeout=5).close()


def send(truck_id, lat, lng):
    post("/api/trucks/location", {
        "truckId": truck_id,
        "latitude": lat,
        "longitude": lng,
        "timestamp": int(time.time() * 1000),
    })


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
    step_metres = 8  # per update: ~15 km/h
    if "--fast" in sys.argv:
        step_metres = 80

    truck = db.data["trucks"].get(truck_id)
    if not truck or not truck.get("stops"):
        sys.exit(f"{truck_id} needs stops first (admin panel, or the driver app's Add stop button).")

    stops = truck["stops"]
    print(f"Driving {truck_id} through {len(stops)} stops, {step_metres} m every {UPDATE_SECONDS} s.", flush=True)

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
            if distance < step_metres and not is_last_point:
                continue  # road points closer than one step: skip ahead

            # Move toward the point in step_metres steps.
            steps = max(1, int(distance // step_metres))
            start_lat = lat
            start_lng = lng
            for step in range(1, steps + 1):
                lat = start_lat + (target_lat - start_lat) * step / steps
                lng = start_lng + (target_lng - start_lng) * step / steps
                send(truck_id, lat, lng)
                time.sleep(UPDATE_SECONDS)

        # Park there a little, then press "Garbage collected" like the driver.
        print(f"  at stop {i + 1}: {stop['name']}", flush=True)
        for _ in range(PARK_UPDATES):
            send(truck_id, lat, lng)
            time.sleep(UPDATE_SECONDS)
        post("/api/trucks/collected", {"truckId": truck_id, "stopId": stop["id"]})
        print("    collected", flush=True)

    post("/api/trucks/stop-sharing", {"truckId": truck_id})
    print("Round finished.", flush=True)


if __name__ == "__main__":
    main()
