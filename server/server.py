"""Kachra Free server. Python standard library only: nothing to install.

    python server.py

Serves the JSON API the two Android apps call (docs/EXPLAINER.md) and the
admin panel at /admin. Data lives in data/ (see db.py).
"""

import base64
import hmac
import json
import os
import secrets
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import alerts
import db
import eta
import osm
import routing

PORT = 8080
ADMIN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "admin")
PASSWORD_FILE = os.path.join(db.DATA_DIR, "admin_password.txt")


class BadRequest(Exception):
    pass


# The last location-search failure, for the admin panel's Alerts.
last_search_error = None
last_search_error_at = 0


def load_admin_password():
    """A random password generated on the first run and kept in
    data/admin_password.txt (gitignored). Edit that file to change it."""
    if not os.path.exists(PASSWORD_FILE):
        os.makedirs(db.DATA_DIR, exist_ok=True)
        with open(PASSWORD_FILE, "w", encoding="utf-8") as f:
            f.write(secrets.token_urlsafe(9))
    with open(PASSWORD_FILE, encoding="utf-8") as f:
        return f.read().strip()


ADMIN_PASSWORD = load_admin_password()


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def clean_username(value):
    """A resident's username, as typed in the app. Upper/lower case and
    surrounding spaces don't matter: "Asha" and " asha " are the same
    resident. Allowed: 3 to 30 letters, digits, ".", "_" or "-"."""
    username = str(value or "").strip().lower()
    if len(username) < 3 or len(username) > 30:
        raise BadRequest("username must be 3 to 30 characters")
    for ch in username:
        if not (ch.isalnum() or ch in "._-"):
            raise BadRequest("username can only have letters, numbers, '.', '_' and '-'")
    return username


def new_id():
    return secrets.token_hex(4)


def stops_for_driver(truck_id):
    """The truck's stops for the driver app, with today's status of each
    ("collected", "skipped", "next" or "pending"). Caller holds db.lock."""
    truck = db.data["trucks"][truck_id]
    statuses = eta.stop_statuses(db.data, truck_id)
    stops = []
    for i, stop in enumerate(truck["stops"]):
        stops.append({"id": stop["id"], "name": stop["name"], "latitude": stop["lat"],
                      "longitude": stop["lng"], "status": statuses[i]})
    return stops


def get_truck(truck_id):
    """Caller holds db.lock."""
    truck = db.data["trucks"].get(truck_id)
    if truck is None:
        raise BadRequest(f"no truck with ID {truck_id}")
    return truck


# ------------------------------------------------------ driver-app API --
#
# Trucks are added in the admin panel. The driver app only works with a
# Truck ID the server already knows (404 otherwise).

def truck_info(request):
    """The driver app checks its Truck ID with this before it starts
    tracking. 404 if there's no such truck."""
    truck_id = (request.query.get("truckId") or "").strip()
    with db.lock:
        truck = db.data["trucks"].get(truck_id)
        if truck is None:
            return 404, {"error": "unknown truck"}
        return 200, {"ok": True, "stops": stops_for_driver(truck_id)}


def truck_location(request):
    body = request.json_body()
    truck_id = str(body.get("truckId") or "").strip()
    lat = body.get("latitude")
    lng = body.get("longitude")
    if not truck_id or not is_number(lat) or not is_number(lng):
        raise BadRequest("truckId, latitude and longitude are required")

    with db.lock:
        truck = db.data["trucks"].get(truck_id)
        if truck is None:
            return 404, {"error": "unknown truck"}
        now = time.time()
        truck["lat"] = lat
        truck["lng"] = lng
        truck["timestamp"] = body.get("timestamp")
        truck["lastSeen"] = now
        truck["sharing"] = True
        db.append_track(truck_id, eta.today(), lat, lng, now)
        alerts.check_alerts(db.data, truck_id)
        db.save()
        # Sending the stop list back on every update keeps the driver app in
        # sync with admin edits, with no extra request.
        return 200, {"ok": True, "stops": stops_for_driver(truck_id)}


def truck_stop_sharing(request):
    """The driver pressed "Stop sharing location": the truck is offline
    straight away, rather than after ACTIVE_SECONDS of silence."""
    truck_id = str(request.json_body().get("truckId") or "").strip()
    with db.lock:
        truck = db.data["trucks"].get(truck_id)
        if truck is None:
            return 404, {"error": "unknown truck"}
        truck["sharing"] = False
        db.save()
    return 200, {"ok": True}


def truck_collected(request):
    """The driver pressed "Garbage collected" at a stop."""
    body = request.json_body()
    truck_id = str(body.get("truckId") or "").strip()
    stop_id = body.get("stopId")
    with db.lock:
        truck = db.data["trucks"].get(truck_id)
        if truck is None:
            return 404, {"error": "unknown truck"}
        stop_index = None
        for i, stop in enumerate(truck["stops"]):
            if stop["id"] == stop_id:
                stop_index = i
        if stop_index is None:
            raise BadRequest("no such stop on this truck")
        eta.mark_collected(db.data, truck_id, stop_index)
        alerts.check_alerts(db.data, truck_id)
        db.save()
        return 200, {"ok": True, "stops": stops_for_driver(truck_id)}


def truck_add_stop(request):
    """The driver app's "Add stop here" button: a new collection point at
    the end of this truck's collection order."""
    body = request.json_body()
    truck_id = str(body.get("truckId") or "").strip()
    lat = body.get("latitude")
    lng = body.get("longitude")
    if not truck_id or not is_number(lat) or not is_number(lng):
        raise BadRequest("truckId, latitude and longitude are required")

    with db.lock:
        truck = db.data["trucks"].get(truck_id)
        if truck is None:
            return 404, {"error": "unknown truck"}
        stops = truck["stops"]
        stops.append({
            "id": new_id(),
            "name": f"Stop {len(stops) + 1}",
            "lat": lat,
            "lng": lng,
            "addedBy": "driver",
        })
        db.save()
        return 200, {"ok": True, "stops": stops_for_driver(truck_id)}


# ---------------------------------------------------- resident-app API --

def save_resident(body, from_admin):
    """Used by the app (register, or register again) and the admin panel
    (edit only: residents register themselves in the app).

    Residents are stored by username, so there's never a duplicate: the same
    username registering again - on the same phone or a new one - is the
    same resident, and their record is updated. Returns the username, or
    None if the truck doesn't exist."""
    username = clean_username(body.get("username"))
    truck_id = str(body.get("truckId") or "").strip()
    lat = body.get("latitude")
    lng = body.get("longitude")
    alert_minutes = body.get("alertMinutes")
    if not truck_id or not is_number(lat) or not is_number(lng) or not is_number(alert_minutes):
        raise BadRequest("username, truckId, latitude, longitude and alertMinutes are required")
    if alert_minutes < 1 or alert_minutes > 120:
        raise BadRequest("alertMinutes must be from 1 to 120")

    with db.lock:
        if truck_id not in db.data["trucks"]:
            return None
        if username not in db.data["residents"]:
            if from_admin:
                raise BadRequest("no such resident: the admin panel can only edit residents")
            db.data["residents"][username] = {}
        resident = db.data["residents"][username]
        resident["truckId"] = truck_id
        resident["lat"] = lat
        resident["lng"] = lng
        resident["alertMinutes"] = alert_minutes
        resident["address"] = body.get("address") or None
        resident["updatedAt"] = time.time()
        db.save()
    return username


def resident_register(request):
    if save_resident(request.json_body(), from_admin=False) is None:
        return 404, {"error": "unknown truck"}
    return 200, {"ok": True}


def resident_status(request):
    username = clean_username(request.query.get("username"))

    with db.lock:
        resident = db.data["residents"].get(username)
        if not resident:
            return 404, {"error": "resident not found"}
        status = eta.resident_status(db.data, resident)
        alert_id = f"{username}|{status['runId']}"
        alert = db.data["alerts"].get(alert_id)

    if alert:
        status["alert"] = {"id": alert_id, "message": alert["message"]}
    else:
        status["alert"] = None
    return 200, status


def places_search(request):
    global last_search_error, last_search_error_at
    query = (request.query.get("query") or "").strip()
    if not query:
        return 200, {"results": []}
    try:
        return 200, {"results": osm.search(query)}
    except Exception as error:
        last_search_error = f"Location search (Nominatim) failed: {error}"
        last_search_error_at = time.time()
        return 502, {"error": f"location search failed: {error}"}


def places_suggest(request):
    """Search suggestions while the resident is typing (osm.suggest)."""
    global last_search_error, last_search_error_at
    query = (request.query.get("query") or "").strip()
    if len(query) < 3:
        return 200, {"results": []}  # too short to suggest anything useful

    # The middle of the resident's map, so nearby places come first.
    lat = None
    lng = None
    try:
        lat = float(request.query["lat"])
        lng = float(request.query["lng"])
    except (KeyError, ValueError):
        pass  # not sent: no nearby preference

    try:
        return 200, {"results": osm.suggest(query, lat, lng)}
    except Exception as error:
        last_search_error = f"Search suggestions (Photon) failed: {error}"
        last_search_error_at = time.time()
        return 502, {"error": f"search suggestions failed: {error}"}


def ping(request):
    """Lets the apps check that the server is reachable."""
    return 200, {"ok": True}


def system_alerts(trucks):
    """Problems an admin should know about, for the admin panel's Alerts."""
    found = []
    for truck in trucks:
        if truck["routingError"]:
            found.append(f"Truck {truck['id']}: {truck['routingError']}. Its ETAs use a rough "
                         "distance ÷ average-car-speed estimate until the route server answers again.")
    seconds_ago = time.time() - last_search_error_at
    if last_search_error and seconds_ago < 3600:
        minutes = round(seconds_ago / 60)
        found.append(f"{last_search_error} ({minutes} min ago)")
    return found


# -------------------------------------------------------------- admin --

def admin_state(request):
    with db.lock:
        data = db.data
        now = time.time()

        trucks = []
        for truck_id, truck in sorted(data["trucks"].items()):
            statuses = eta.stop_statuses(data, truck_id)
            collected_today = 0
            for status in statuses:
                if status == "collected":
                    collected_today += 1

            # Earlier days of history: real ones and made-up demo ones.
            past_runs = 0
            demo_runs = 0
            for run in data["runs"].values():
                if run["truckId"] != truck_id or run["date"] == eta.today():
                    continue
                if run["synthetic"]:
                    demo_runs += 1
                else:
                    past_runs += 1

            seconds_since_update = None
            if "lastSeen" in truck:
                seconds_since_update = now - truck["lastSeen"]

            route_age_seconds = None
            approach = truck.get("approach")
            if approach:
                route_age_seconds = now - approach["computedAt"]

            stops = []
            for i, stop in enumerate(truck["stops"]):
                shape, source = eta.leg_shape(truck, i)
                stops.append({
                    "id": stop["id"], "name": stop["name"], "lat": stop["lat"], "lng": stop["lng"],
                    "addedBy": stop["addedBy"],
                    "status": statuses[i],  # today: collected / skipped / next / pending
                    "usualTime": eta.usual_time(data, truck_id, stop["id"]),
                    "typicalDwellSeconds": round(eta.typical_dwell(data, truck_id, stop["id"])),
                    # Road to the next stop: as driven, the OSRM route, or none yet.
                    "legPoints": shape,
                    "legSource": source,
                })
            trucks.append({
                "id": truck_id,
                "lat": truck.get("lat"),
                "lng": truck.get("lng"),
                "secondsSinceUpdate": seconds_since_update,
                "active": eta.is_active(truck),
                "stops": stops,
                "collectedToday": collected_today,
                "pastRuns": past_runs,
                "demoRuns": demo_runs,
                "routeAgeSeconds": route_age_seconds,
                "routingError": truck.get("routingError"),
            })

        residents = []
        for username, resident in sorted(data["residents"].items()):
            status = eta.resident_status(data, resident)
            residents.append({
                "username": username,
                "truckId": resident["truckId"],
                "lat": resident["lat"],
                "lng": resident["lng"],
                "address": resident["address"],
                "alertMinutes": resident["alertMinutes"],
                "stopName": status["stopName"],
                "stopDistanceMeters": status["stopDistanceMeters"],
                "status": status["status"],
                "message": status["message"],
            })

        settings = dict(data["settings"])

    return 200, {"alerts": system_alerts(trucks), "trucks": trucks,
                 "residents": residents, "settings": settings}


def admin_add_truck(request):
    truck_id = str(request.json_body().get("id") or "").strip()
    if not truck_id:
        raise BadRequest("id is required")
    with db.lock:
        if truck_id not in db.data["trucks"]:
            db.data["trucks"][truck_id] = {"stops": []}
        db.save()
    return 200, {"ok": True}


def admin_delete_truck(request):
    with db.lock:
        db.data["trucks"].pop(request.query.get("id"), None)
        db.save()
    return 200, {"ok": True}


def admin_save_stops(request):
    """Replaces a truck's stop list (the admin's reorder/rename/add/remove).
    Stops that keep their id keep their cached road data."""
    body = request.json_body()
    stops = body.get("stops")
    if not isinstance(stops, list):
        raise BadRequest("stops must be a list")

    with db.lock:
        truck = get_truck(str(body.get("truckId") or ""))

        # The truck's current stops, by id.
        existing = {}
        for stop in truck["stops"]:
            existing[stop["id"]] = stop

        new_stops = []
        for i, stop in enumerate(stops):
            if not isinstance(stop, dict) or not is_number(stop.get("lat")) or not is_number(stop.get("lng")):
                raise BadRequest("every stop needs a numeric lat and lng")

            if stop.get("id") in existing:
                # A stop we already had: start from a copy of it, keeping its road data.
                new_stop = dict(existing[stop["id"]])
            else:
                # A new stop from the admin panel.
                new_stop = {"id": new_id(), "addedBy": "admin"}

            new_stop["name"] = str(stop.get("name") or f"Stop {i + 1}")
            new_stop["lat"] = stop["lat"]
            new_stop["lng"] = stop["lng"]
            new_stops.append(new_stop)
        truck["stops"] = new_stops
        db.save()
    return 200, {"ok": True}


def admin_demo_history(request):
    body = request.json_body()
    with db.lock:
        truck_id = str(body.get("truckId") or "")
        truck = get_truck(truck_id)
        if body.get("action") == "clear":
            count = eta.clear_demo_history(db.data, truck_id)
        else:
            if not truck.get("stops"):
                raise BadRequest("add stops to this truck first")
            count = eta.generate_demo_history(db.data, truck_id)
        db.save()
    return 200, {"runs": count}


def admin_track(request):
    """The roads a truck actually drove on a day: its raw GPS points."""
    truck_id = request.query.get("truckId") or ""
    date = request.query.get("date") or eta.today()
    points = []
    for lat, lng, t in db.read_track(truck_id, date):
        points.append([lat, lng])  # the map doesn't need the times
    return 200, {"points": points}


def admin_history(request):
    """A truck's past collection rounds, newest first, for the admin panel's
    history table. For each day and each of its current stops: {collectedAt}
    for a real round, {arrived, left} for a demo round, or None if the stop
    wasn't collected that day."""
    with db.lock:
        truck = get_truck(request.query.get("truckId") or "")
        truck_id = request.query.get("truckId")

        stops = []
        for stop in truck["stops"]:
            stops.append({
                "name": stop["name"],
                "usualTime": eta.usual_time(db.data, truck_id, stop["id"]),
                "typicalDwellSeconds": round(eta.typical_dwell(db.data, truck_id, stop["id"])),
            })

        days = []
        for run in db.data["runs"].values():
            if run["truckId"] != truck_id:
                continue
            visits = []
            for stop in truck["stops"]:
                visit = run["visits"].get(stop["id"])
                if visit is None:
                    visits.append(None)
                else:
                    # Real rounds: when it was collected. Demo rounds: arrival
                    # and departure (their difference is the stoppage time).
                    visits.append(dict(visit))
            days.append({
                "date": run["date"],
                "synthetic": run["synthetic"],
                "today": run["date"] == eta.today(),
                "visits": visits,
            })

    # Newest day first. (ISO dates like "2026-09-27" sort correctly as text.)
    days.sort(key=get_date, reverse=True)
    return 200, {"truckId": truck_id, "stops": stops, "days": days}


def get_date(day):
    return day["date"]


def admin_save_resident(request):
    username = save_resident(request.json_body(), from_admin=True)
    if username is None:
        raise BadRequest("unknown truck")
    return 200, {"username": username}


def admin_delete_resident(request):
    # Exactly as listed in the admin panel: no username rules needed to delete.
    username = request.query.get("username") or ""
    with db.lock:
        db.data["residents"].pop(username, None)
        db.save()
    return 200, {"ok": True}


def admin_save_settings(request):
    body = request.json_body()
    with db.lock:
        settings = db.data["settings"]
        for key in ("stopRadiusMeters", "secondsPerStop", "routeRefreshMinutes", "fallbackSpeedKmh"):
            if key in body:
                if not is_number(body[key]) or body[key] <= 0:
                    raise BadRequest(f"{key} must be a positive number")
                settings[key] = body[key]
        db.save()
    return 200, {"ok": True}


ROUTES = {
    ("POST", "/api/trucks/location"): truck_location,
    ("GET", "/api/trucks"): truck_info,
    ("POST", "/api/trucks/stops"): truck_add_stop,
    ("POST", "/api/trucks/stop-sharing"): truck_stop_sharing,
    ("POST", "/api/trucks/collected"): truck_collected,
    ("POST", "/api/residents/register"): resident_register,
    ("GET", "/api/residents/status"): resident_status,
    ("GET", "/api/places/search"): places_search,
    ("GET", "/api/places/suggest"): places_suggest,
    ("GET", "/api/ping"): ping,
    ("GET", "/api/admin/state"): admin_state,
    ("POST", "/api/admin/trucks"): admin_add_truck,
    ("DELETE", "/api/admin/trucks"): admin_delete_truck,
    ("POST", "/api/admin/trucks/stops"): admin_save_stops,
    ("POST", "/api/admin/trucks/demo-history"): admin_demo_history,
    ("GET", "/api/admin/track"): admin_track,
    ("GET", "/api/admin/history"): admin_history,
    ("POST", "/api/admin/residents"): admin_save_resident,
    ("DELETE", "/api/admin/residents"): admin_delete_resident,
    ("POST", "/api/admin/settings"): admin_save_settings,
}

STATIC_FILES = {
    "/admin": ("index.html", "text/html; charset=utf-8"),
    "/admin/admin.js": ("admin.js", "text/javascript; charset=utf-8"),
    "/admin/admin.css": ("admin.css", "text/css; charset=utf-8"),
}


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def do_DELETE(self):
        self.handle_request("DELETE")

    def handle_request(self, method):
        url = urllib.parse.urlparse(self.path)
        path = url.path.rstrip("/") or "/"

        # "?username=asha&x=1" -> {"username": "asha", "x": "1"}. parse_qs gives a
        # list per name (a name can repeat); we only ever need the first.
        self.query = {}
        for name, values in urllib.parse.parse_qs(url.query).items():
            self.query[name] = values[0]

        if path == "/":
            self.send_response(302)
            self.send_header("Location", "/admin")
            self.end_headers()
            return

        if path.startswith("/admin") or path.startswith("/api/admin"):
            if not self.is_admin():
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="Kachra Free admin", charset="UTF-8"')
                self.end_headers()
                return

        if method == "GET" and path in STATIC_FILES:
            name, content_type = STATIC_FILES[path]
            self.send_file(name, content_type)
            return

        handler = ROUTES.get((method, path))
        if handler is None:
            self.send_json(404, {"error": "not found"})
            return
        try:
            status, body = handler(self)
        except BadRequest as error:
            status, body = 400, {"error": str(error)}
        self.send_json(status, body)

    def is_admin(self):
        # The browser sends "Authorization: Basic <base64 of user:password>".
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(header[len("Basic "):]).decode("utf-8")
        except ValueError:
            return False
        user, _, password = decoded.partition(":")
        # compare_digest takes the same time whether or not the password is
        # right, so response timing can't give it away.
        return user == "admin" and hmac.compare_digest(password.encode(), ADMIN_PASSWORD.encode())

    def json_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            raise BadRequest("body must be JSON")
        if not isinstance(body, dict):
            raise BadRequest("body must be a JSON object")
        return body

    def send_json(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def send_file(self, name, content_type):
        with open(os.path.join(ADMIN_DIR, name), "rb") as f:
            payload = f.read()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)


def main():
    threading.Thread(target=routing.updater, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Kachra Free server on http://localhost:{PORT}", flush=True)
    print(f"Admin panel: http://localhost:{PORT}/admin  (user: admin, password: {ADMIN_PASSWORD})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
