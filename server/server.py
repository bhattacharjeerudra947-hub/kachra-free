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
    """ADMIN_PASSWORD env var if set, otherwise a random password generated
    once and kept in data/admin_password.txt (gitignored)."""
    if os.environ.get("ADMIN_PASSWORD"):
        return os.environ["ADMIN_PASSWORD"]
    try:
        with open(PASSWORD_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except FileNotFoundError:
        password = secrets.token_urlsafe(9)
        os.makedirs(db.DATA_DIR, exist_ok=True)
        with open(PASSWORD_FILE, "w", encoding="utf-8") as f:
            f.write(password)
        return password


ADMIN_PASSWORD = load_admin_password()


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def normalize_phone(value):
    """"+91 98765-43210" and "+919876543210" are the same resident."""
    phone = ""
    for ch in str(value or ""):
        if ch.isdigit() or ch == "+":
            phone += ch
    return phone


def new_id():
    return secrets.token_hex(4)


def stops_for_driver(truck):
    stops = []
    for stop in truck.get("stops", []):
        stops.append({"name": stop["name"], "latitude": stop["lat"], "longitude": stop["lng"]})
    return stops


def get_truck(truck_id):
    """Caller holds db.lock."""
    truck = db.data["trucks"].get(truck_id)
    if truck is None:
        raise BadRequest(f"no truck with ID {truck_id}")
    return truck


# ------------------------------------------------------ driver-app API --

def truck_location(request):
    body = request.json_body()
    truck_id = str(body.get("truckId") or "").strip()
    lat = body.get("latitude")
    lng = body.get("longitude")
    if not truck_id or not is_number(lat) or not is_number(lng):
        raise BadRequest("truckId, latitude and longitude are required")

    with db.lock:
        # Unknown IDs are added automatically, so a new driver just types an
        # ID and starts. The truck then shows up in the admin panel.
        if truck_id not in db.data["trucks"]:
            db.data["trucks"][truck_id] = {"stops": []}
        truck = db.data["trucks"][truck_id]
        now = time.time()
        truck["lat"] = lat
        truck["lng"] = lng
        truck["timestamp"] = body.get("timestamp")
        truck["lastSeen"] = now
        db.append_track(truck_id, eta.today(), lat, lng, now)
        eta.record_position(db.data, truck_id)
        alerts.check_alerts(db.data, truck_id)
        db.save()
        # Sending the stop list back on every update keeps the driver app in
        # sync with admin edits, with no extra request.
        return 200, {"ok": True, "stops": stops_for_driver(truck)}


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
        if truck_id not in db.data["trucks"]:
            db.data["trucks"][truck_id] = {"stops": []}
        truck = db.data["trucks"][truck_id]
        stops = truck["stops"]
        stops.append({
            "id": new_id(),
            "name": f"Stop {len(stops) + 1}",
            "lat": lat,
            "lng": lng,
            "addedBy": "driver",
        })
        db.save()
        return 200, {"ok": True, "stops": stops_for_driver(truck)}


# ---------------------------------------------------- resident-app API --

def save_resident(body, from_admin):
    """Used by both the app and the admin panel. Both create the same kind of
    resident record (AGENTS.md section 13)."""
    phone = normalize_phone(body.get("phoneNumber"))
    truck_id = str(body.get("truckId") or "").strip()
    lat = body.get("latitude")
    lng = body.get("longitude")
    alert_minutes = body.get("alertMinutes")
    if not phone or not truck_id or not is_number(lat) or not is_number(lng) or not is_number(alert_minutes):
        raise BadRequest("phoneNumber, truckId, latitude, longitude and alertMinutes are required")

    with db.lock:
        if truck_id not in db.data["trucks"]:
            return None
        if phone not in db.data["residents"]:
            # "source" records who registered them first: the app or the admin.
            db.data["residents"][phone] = {"source": "admin" if from_admin else "app"}
        resident = db.data["residents"][phone]
        resident["truckId"] = truck_id
        resident["lat"] = lat
        resident["lng"] = lng
        resident["alertMinutes"] = alert_minutes
        resident["address"] = body.get("address") or None
        resident["updatedAt"] = time.time()
        if from_admin:
            resident["name"] = str(body.get("name") or "").strip()
        db.save()
    return phone


def resident_register(request):
    if save_resident(request.json_body(), from_admin=False) is None:
        return 404, {"error": "unknown truck"}
    return 200, {"ok": True}


def resident_status(request):
    phone = normalize_phone(request.query.get("phone"))
    if not phone:
        raise BadRequest("phone is required")

    with db.lock:
        resident = db.data["residents"].get(phone)
        if not resident:
            return 404, {"error": "resident not found"}
        status = eta.resident_status(db.data, resident)
        alert_id = f"{phone}|{status['runId']}"
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
    with db.lock:
        country_codes = db.data["settings"]["searchCountryCodes"]
    try:
        return 200, {"results": osm.search(query, country_codes)}
    except Exception as error:
        last_search_error = str(error)
        last_search_error_at = time.time()
        return 502, {"error": f"location search failed: {error}"}


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
        found.append(f"Location search (Nominatim) failed {minutes} min ago: {last_search_error}")
    return found


# -------------------------------------------------------------- admin --

def admin_state(request):
    with db.lock:
        data = db.data
        now = time.time()

        trucks = []
        for truck_id, truck in sorted(data["trucks"].items()):
            last, _ = eta.progress(data, truck_id)

            # Earlier days of history: real ones and made-up demo ones.
            past_runs = 0
            demo_runs = 0
            for run in data["runs"].values():
                if run["truckId"] != truck_id or run["date"] == eta.today():
                    continue
                if run.get("synthetic"):
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
            for i, stop in enumerate(truck.get("stops", [])):
                shape, source = eta.leg_shape(truck, i)
                stops.append({
                    "id": stop["id"], "name": stop["name"], "lat": stop["lat"], "lng": stop["lng"],
                    "addedBy": stop.get("addedBy", "admin"),
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
                "stopsVisitedToday": last + 1,
                "pastRuns": past_runs,
                "demoRuns": demo_runs,
                "routeAgeSeconds": route_age_seconds,
                "routingError": truck.get("routingError"),
            })

        residents = []
        for phone, resident in sorted(data["residents"].items()):
            status = eta.resident_status(data, resident)
            residents.append({
                "phoneNumber": phone,
                "name": resident.get("name", ""),
                "truckId": resident.get("truckId"),
                "lat": resident["lat"],
                "lng": resident["lng"],
                "address": resident.get("address"),
                "alertMinutes": resident["alertMinutes"],
                "source": resident.get("source", "app"),
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
        for stop in truck.get("stops", []):
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


def admin_save_resident(request):
    phone = save_resident(request.json_body(), from_admin=True)
    if phone is None:
        raise BadRequest("unknown truck")
    return 200, {"phoneNumber": phone}


def admin_delete_resident(request):
    with db.lock:
        db.data["residents"].pop(normalize_phone(request.query.get("phone")), None)
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
        if "searchCountryCodes" in body:
            settings["searchCountryCodes"] = str(body["searchCountryCodes"]).strip()
        db.save()
    return 200, {"ok": True}


ROUTES = {
    ("POST", "/api/trucks/location"): truck_location,
    ("POST", "/api/trucks/stops"): truck_add_stop,
    ("POST", "/api/residents/register"): resident_register,
    ("GET", "/api/residents/status"): resident_status,
    ("GET", "/api/places/search"): places_search,
    ("GET", "/api/admin/state"): admin_state,
    ("POST", "/api/admin/trucks"): admin_add_truck,
    ("DELETE", "/api/admin/trucks"): admin_delete_truck,
    ("POST", "/api/admin/trucks/stops"): admin_save_stops,
    ("POST", "/api/admin/trucks/demo-history"): admin_demo_history,
    ("GET", "/api/admin/track"): admin_track,
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

        # "?phone=123&x=1" -> {"phone": "123", "x": "1"}. parse_qs gives a
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
