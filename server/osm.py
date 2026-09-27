"""Free map services from OpenStreetMap: no API key, no account, no bill.

- route():  OSRM. Driving route through points along real roads, split into
            legs, each with its normal drive time (no live traffic).
- search(): Nominatim. Place/address search for the resident app's picker
            (when the resident presses Go).
- suggest(): Photon. Suggestions while the resident is still typing, e.g.
            "india ga" -> India Gate. Nominatim's rules forbid
            search-as-you-type; Photon is the OpenStreetMap search made for it.

All three public servers ask for fair use: an honest User-Agent and at most
about one request per second. _get_json() spaces requests out to respect
that. A real deployment would run its own copies of these servers.
"""

import json
import threading
import time
import urllib.parse
import urllib.request

OSRM_URL = "https://router.project-osrm.org/route/v1/driving/"
NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
PHOTON_URL = "https://photon.komoot.io/api/"
USER_AGENT = "KachraFree/1.0 (student prototype)"

_turn = threading.Lock()
_last_request_at = 0.0
_search_cache = {}
_suggest_cache = {}


def _get_json(url):
    global _last_request_at
    # One request at a time, at least a second apart (the servers' usage policy).
    with _turn:
        wait = 1.0 - (time.time() - _last_request_at)
        if wait > 0:
            time.sleep(wait)
        _last_request_at = time.time()
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def route(points):
    """Driving route through points [(lat, lng), ...], in order. Returns one
    leg per consecutive pair:
        [{"seconds": 286.0, "meters": 1795.0, "points": [[lat, lng], ...]}, ...]"""
    # OSRM wants "lng,lat;lng,lat;..." (longitude first).
    parts = []
    for lat, lng in points:
        parts.append(f"{lng},{lat}")
    coordinates = ";".join(parts)
    data = _get_json(f"{OSRM_URL}{coordinates}?overview=false&steps=true&geometries=geojson")
    if data.get("code") != "Ok" or not data.get("routes"):
        raise ValueError(f"no road route found ({data.get('code')})")

    legs = []
    for leg in data["routes"][0]["legs"]:
        # A leg's road shape is the joined-up shapes of its turn-by-turn steps.
        road = []
        for step in leg["steps"]:
            for lng, lat in step["geometry"]["coordinates"]:  # GeoJSON order is lng, lat
                if not road or road[-1] != [lat, lng]:
                    road.append([lat, lng])
        legs.append({"seconds": leg["duration"], "meters": leg["distance"], "points": road})
    return legs


def search(query):
    """Returns up to 5 [{"name", "latitude", "longitude"}]. Results are cached,
    since the same few searches repeat and the server asks us to go easy."""
    key = query.lower()
    if key not in _search_cache:
        params = {"format": "jsonv2", "limit": 5, "q": query}
        found = _get_json(f"{NOMINATIM_URL}?{urllib.parse.urlencode(params)}")

        results = []
        for place in found:
            results.append({
                "name": place["display_name"],
                "latitude": float(place["lat"]),
                "longitude": float(place["lon"]),
            })

        if len(_search_cache) > 500:
            _search_cache.clear()  # keep memory use small
        _search_cache[key] = results
    return _search_cache[key]


def photon_name(properties):
    """A readable one-line name from a Photon result's parts, e.g.
    "India Gate, Shahjahan Road, New Delhi, Delhi, India"."""
    street = properties.get("street", "")
    if street and properties.get("housenumber"):
        street = properties["housenumber"] + " " + street

    parts = []
    for part in (properties.get("name"), street, properties.get("district"),
                 properties.get("city"), properties.get("state"), properties.get("country")):
        if part and part not in parts:  # skip empty and repeated parts
            parts.append(part)
    return ", ".join(parts)


def suggest(query, lat=None, lng=None):
    """Up to 5 [{"name", "latitude", "longitude"}] for a half-typed query.
    Places near (lat, lng), the middle of the resident's map, come first."""
    # Round the position (about 10 km) so small map moves reuse the cache.
    if lat is not None and lng is not None:
        lat = round(lat, 1)
        lng = round(lng, 1)
    key = (query.lower(), lat, lng)

    if key not in _suggest_cache:
        params = {"q": query, "limit": 5}
        if lat is not None and lng is not None:
            params["lat"] = lat
            params["lon"] = lng
        found = _get_json(f"{PHOTON_URL}?{urllib.parse.urlencode(params)}")

        results = []
        for feature in found["features"]:
            lng_found, lat_found = feature["geometry"]["coordinates"]  # GeoJSON order is lng, lat
            results.append({"name": photon_name(feature["properties"]), "latitude": lat_found, "longitude": lng_found})

        if len(_suggest_cache) > 500:
            _suggest_cache.clear()
        _suggest_cache[key] = results
    return _suggest_cache[key]
