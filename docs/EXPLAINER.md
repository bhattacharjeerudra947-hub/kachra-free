# Kachra Free — Full Technical Explainer

A complete description of what this repository does and how: every feature,
every calculation, every endpoint, every dependency and setting. AGENTS.md
says *what* the system should do; this file says exactly how the current code
does it. Setup and launch steps are in [SETUP.md](SETUP.md).

**Contents**

1. [Overview](#1-overview)
2. [Repository layout](#2-repository-layout)
3. [Dependencies and external services](#3-dependencies-and-external-services)
4. [Core concepts](#4-core-concepts)
5. [driver-app](#5-driver-app)
6. [resident-app](#6-resident-app)
7. [Server: runtime and storage](#7-server-runtime-and-storage)
8. [Algorithms and equations](#8-algorithms-and-equations)
9. [API reference](#9-api-reference)
10. [Admin panel](#10-admin-panel)
11. [Settings and constants](#11-settings-and-constants)
12. [Security and keys](#12-security-and-keys)
13. [Networking: reaching the laptop (ngrok)](#13-networking-reaching-the-laptop-ngrok)
14. [Demo tools](#14-demo-tools)
15. [Limitations and deliberate omissions](#15-limitations-and-deliberate-omissions)
16. [How it was tested](#16-how-it-was-tested)

---

## 1. Overview

**The promise:** *know when your garbage truck is coming.*

A driver's phone reports the truck's GPS position every 10 seconds. Each truck
has an ordered list of **stops**: collection points where it parks and nearby
residents bring their garbage out (the truck never goes house to house). A
resident registers with the **Truck ID** of the truck serving their area and
their house location, and is served at that truck's stop nearest their house.
The server works out when the truck will reach that stop and alerts the
resident once, when it's within the number of minutes they chose.

```text
 driver-app ── POST /api/trucks/location every 10 s ─▶ ┌────────────────────────────────┐
            ◀─ this truck's stops (two-way sync) ──────│ server/  (Python stdlib)       │
            ── POST /api/trucks/stops  ("Add stop") ─▶ │  stop visits → history         │◀─▶ OSRM
                                                      │  roads driven → GPS tracks     │    (road routes,
 resident-app                                         │  ETA = road drive time         │     drive times)
   AlertService ── GET /api/residents/status /15 s ──▶ │      + typical collecting time │◀─▶ Nominatim
   MainActivity ── POST /api/residents/register ─────▶ │  threshold alerts (once/round) │    (place search)
   Picker       ── GET /api/places/search ───────────▶ │  data/db.json + data/tracks/   │
 admin (browser) ── /admin, /api/admin/* ────────────▶ └────────────────────────────────┘
                                                          ▲
                                           ngrok tunnel (public https URL → laptop:8080)
```

Design rules the code follows throughout:

- **Minimal:** no frameworks, no database, no networking libraries. The server
  is Python's standard library only.
- **Free:** no API keys, accounts or cards anywhere. Maps, routing and search
  are OpenStreetMap services.
- **Local-first apps:** saving on the phone always works; a failed server call
  means "try again next time", never a crash or a blocked screen.
- **No straight lines:** a road between two points is either the road the
  truck actually drove or a real road route. A leg with neither isn't drawn.

---

## 2. Repository layout

```text
kachra-free/
├── README.md                      Short intro and quick start
├── docs/
│   ├── AGENTS.md                  The product specification
│   ├── EXPLAINER.md               This file
│   ├── SETUP.md                   Installing tools, building, running a demo
│   ├── TODO_FOR_YOU.md            What still needs a human (real-phone testing)
│   └── LOG.md                     (empty, yours)
├── driver-app/                    Android app for the truck
│   ├── build.gradle.kts           Plugin versions
│   ├── settings.gradle.kts        Repositories, project name
│   ├── gradle.properties          Gradle/AndroidX switches
│   ├── gradlew, gradlew.bat       Gradle wrapper (downloads Gradle 9.1.0)
│   └── app/
│       ├── build.gradle.kts       SDK levels, dependencies
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/               Adaptive launcher icon, app name, theme
│           └── java/com/example/kachrafreedriver/
│               ├── MainActivity.kt      Screen state, Add-stop timer, permissions
│               ├── LocationService.kt   Foreground GPS service + upload
│               ├── ServerApi.kt         The two HTTP calls
│               ├── ServerConfig.kt      BASE_URL
│               ├── AppPrefs.kt          SharedPreferences keys
│               ├── theme/Theme.kt       Colours
│               └── ui/main/MainScreen.kt  The one screen
├── resident-app/                  Android app for residents
│   └── app/src/main/java/com/example/kachrafreeresident/
│       ├── MainActivity.kt              Screen switching, registration
│       ├── AlertService.kt              Foreground service: polls status, posts alerts
│       ├── BootReceiver.kt              Restarts AlertService after reboot
│       ├── LocationPickerActivity.kt    House picker: search, locate-me, geocode
│       ├── ServerApi.kt                 The three HTTP calls
│       ├── ServerConfig.kt              BASE_URL
│       ├── AppPrefs.kt                  SharedPreferences keys
│       ├── theme/Theme.kt
│       └── ui/main/
│           ├── RegisterScreen.kt        Registration form
│           ├── StatusScreen.kt          Live status + saved registration
│           ├── TruckMapScreen.kt        House, stop, truck, road
│           ├── LocationPickerScreen.kt  Drop-a-pin map UI
│           └── OsmMap.kt                Shared osmdroid setup, markers, credit
└── server/
    ├── server.py                  HTTP server, routing, auth, every endpoint
    ├── db.py                      JSON "database", lock, GPS track files
    ├── eta.py                     Visits, history, roads, ETA, status
    ├── routing.py                 Background thread fetching OSRM routes
    ├── osm.py                     OSRM + Nominatim clients (rate-limited)
    ├── alerts.py                  Threshold alerts, once per round
    ├── simulate.py                Drives a pretend truck for demos
    ├── admin/                     index.html, admin.js, admin.css
    └── data/                      db.json, admin_password.txt, tracks/ (gitignored)
```

---

## 3. Dependencies and external services

### 3.1 Toolchain

| Tool | Version | Used for |
|---|---|---|
| JDK | 17 | Running Gradle and compiling Kotlin (Java 17 bytecode) |
| Gradle | 9.1.0 (via wrapper) | Building the apps. Downloaded by `gradlew`, not installed |
| Android Gradle Plugin | 9.0.1 | Android build, with built-in Kotlin support |
| Kotlin Compose compiler plugin | 2.3.20 | Compiling Jetpack Compose UI |
| Android SDK | compileSdk/targetSdk 36, minSdk 26 (Android 8.0) | `minSdk` 26 because notification channels don't exist below it |
| Python | 3.10+ (tested on 3.13) | The server. Standard library only |
| ngrok agent | 3.x, free account | Public `https://` address for the laptop |

### 3.2 App libraries

| Library | Version | Apps | Why |
|---|---|---|---|
| `androidx.compose:compose-bom` | 2026.03.01 | both | Pins all Compose library versions together |
| `androidx.compose.ui:ui` | from BOM | both | Compose core (layout, modifiers) |
| `androidx.compose.material3:material3` | from BOM | both | Buttons, text fields, surfaces, theme |
| `androidx.activity:activity-compose` | 1.13.0 | both | `setContent`, permission/activity-result launchers |
| `androidx.core:core` | 1.18.0 | both | `ContextCompat`, `ServiceCompat` |
| `org.osmdroid:osmdroid-android` | 6.1.20 | resident | OpenStreetMap map view, markers, polylines |

Everything else is built into Android: `HttpURLConnection` for HTTP,
`org.json` for JSON, `LocationManager` for GPS, `Geocoder` for reverse
geocoding, `SharedPreferences` for storage, `NotificationManager` for
notifications.

### 3.3 Server libraries

None. Standard library modules used: `http.server` (`ThreadingHTTPServer`),
`json`, `threading`, `urllib.request`/`urllib.parse`, `statistics`, `math`,
`datetime`, `random`, `secrets`, `hmac`, `base64`, `os`.

### 3.4 Admin panel

Plain HTML/CSS/JavaScript, no framework. One library, **Leaflet 1.9.4**,
loaded from the unpkg CDN for the map, with OpenStreetMap tiles.

### 3.5 External services (all free, no key)

| Service | Used by | For | Fair-use handling |
|---|---|---|---|
| **OSRM** public server, `router.project-osrm.org` | server (`osm.py`) | Road routes and normal drive times between points | ≥ 1 s between requests, honest User-Agent, small batches |
| **Nominatim**, `nominatim.openstreetmap.org` | server (`osm.py`) | Place/address search for the resident picker | Same 1 s spacing, results cached |
| **OpenStreetMap tiles**, `tile.openstreetmap.org` | resident app (osmdroid), admin panel (Leaflet) | Map images | User-Agent = app package name; tiles cached on the phone; "© OpenStreetMap contributors" credit shown |
| **Android `Geocoder`** | resident app picker | Address text under the pin | Built into the phone, no key |
| **ngrok** free plan | laptop | Public HTTPS tunnel with one static domain | — |

---

## 4. Core concepts

| Term | Meaning |
|---|---|
| **Truck** | Identified by a free-text **Truck ID** (e.g. `TRUCK-1`). Owns its list of stops. |
| **Stop** | A collection point: `id`, `name`, `lat`, `lng`. Stops are ordered; **the order is the collection order.** |
| **Leg** | The drive from stop *i* to stop *i+1*. |
| **Run** | One truck's collection round on one calendar day. Key `"<truckId>|<YYYY-MM-DD>"`. |
| **Visit** | Within a run, when the truck `arrived` at a stop and when it `left`. `left − arrived` = **dwell**, time spent collecting. |
| **Resident** | Keyed by phone number. Has a Truck ID, house location and alert threshold (5/10/15/30 min). |
| **Collection point** | For a resident: their truck's stop nearest their house. |
| **Approach** | The road route from the truck's current position to its next stop, refreshed periodically. |
| **Synthetic run** | Made-up past history for demos, flagged `synthetic: true`. |

---

## 5. driver-app

**Purpose:** report the truck's location with no interaction while driving,
and let the driver add collection stops (AGENTS.md section 1).

### 5.1 Screen (`MainScreen.kt`)

- **Truck ID** text field (locked while sharing).
- **Location sharing: on/off**, current position with seconds since the fix,
  and **server status** (`connecting…` / `connected, last sent Ns ago` /
  `unreachable, last sent Ns ago`).
- **Start / Stop sharing location** button.
- While sharing: the truck's **Collection stops** list, synced from the
  server, and **Add stop here**. Pressing it starts a **20-second countdown**
  with **Undo**; only after 20 s is the stop sent.

`MainActivity` redraws this once a second (`uiHandler`, 1000 ms) by reading
`LocationService`'s in-memory state, so the screen always shows the service's
real state rather than a saved flag.

### 5.2 `LocationService` (foreground service)

| Aspect | Behaviour |
|---|---|
| Type | Foreground service, `foregroundServiceType="location"`, ongoing notification on channel `kachra_free_tracking` (low importance), id 1001 |
| Fix interval | `UPDATE_INTERVAL_MS = 10 000` ms, `minDistance = 0` m, so a parked truck still reports |
| Providers | Both `GPS_PROVIDER` and `NETWORK_PROVIDER` |
| Duplicate suppression | A network fix is dropped if a GPS fix arrived within the last 2 × 10 s |
| Upload | Each accepted fix → `POST /api/trucks/location` on a background thread. Failures are dropped; the next fix is 10 s later |
| Stop sync | The upload's response contains the truck's stop list → stored in `routeStops` for the screen |
| Location switched off | Tracking does **not** stop: notification says "waiting for GPS"; fixes resume automatically |
| Killed by Android | `START_STICKY`: restarted with a null intent; resumes using the saved Truck ID if `tracking` was true |
| Live state (read by UI) | `isRunning`, `lastLocation` (lat, lng, elapsed-realtime of fix), `lastUploadOk`, `lastUploadAtElapsedMillis`, `routeStops` |

"Seconds ago" uses `SystemClock.elapsedRealtime()`, so changing the phone's
clock doesn't break it.

### 5.3 Add stop (two-way sync)

1. Press **Add stop here** → the *current* fix is captured and a 20 s timer starts.
2. **Undo** within 20 s cancels it.
3. After 20 s → `POST /api/trucks/stops`; the server appends `"Stop N"` to the end
   of the collection order and returns the new list, shown immediately.

Admin edits reach the driver through the stop list returned with every
location upload (≤ 10 s), so there's no separate sync request.

### 5.4 Storage, permissions, networking

- **SharedPreferences `driver_prefs`:** `truck_id`, `tracking` (whether to
  resume after a forced restart).
- **Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`,
  `INTERNET`.
- **HTTP:** `ServerApi.kt`, 5 s connect / 5 s read timeouts, sends
  `ngrok-skip-browser-warning: true`.
- **Unknown Truck IDs are accepted:** the server creates the truck on first
  contact and it appears in the admin panel.

---

## 6. resident-app

**Purpose:** register once, see the truck, its road and the ETA, and get an
alert even when the app is closed (AGENTS.md sections 2, 3, 5).

### 6.1 Screens

| Screen | Contents |
|---|---|
| `RegisterScreen` | Phone number, **Truck ID (required)**, house location (opens the picker), alert range radio buttons **5 / 10 / 15 / 30 min**, Register (or Save/Cancel when editing) |
| `StatusScreen` | Alert banner (if any), the server's status message, **View on map**, the saved registration, **Edit registration** |
| `TruckMapScreen` | osmdroid map: house (blue `#1C7ED6`), collection point (green `#2F9E44`), truck (orange `#F76707`), expected road (primary colour, 10 px). Frames house + stop + truck + road **once**, when the truck first appears (120 px padding), then leaves the map where the user puts it |
| `LocationPickerScreen` | "Drop a pin" picker (below) |

### 6.2 House picker (`LocationPickerActivity` + `LocationPickerScreen`)

Swiggy/Zomato-style: the pin is **fixed at the screen centre** and the map
moves underneath it.

- **Start position:** the previously saved house, or India's approximate
  centre (20.5937, 78.9629). Zoom 17.
- **Settle detection:** osmdroid `DelayedMapListener` fires 400 ms after
  panning/zooming stops → the centre becomes the picked point → Android
  `Geocoder` reverse-geocodes it into an address (on a background thread).
- **Search:** text → `GET /api/places/search` (server → Nominatim) → up to 5
  results; tapping one animates there (600 ms). Different messages for "no
  matches" and "search unavailable".
- **🧭 Use my location:** asks for location permission if needed, uses the last
  known fix if available, otherwise requests **one** fix and unregisters
  immediately. The resident is never tracked.
- **Confirm** returns latitude, longitude and address (if found) to
  `MainActivity`.

### 6.3 Registration

1. Client validation: phone, Truck ID and house must be set.
2. `POST /api/residents/register`:
   - **200** → saved locally, registered.
   - **404 `unknown truck`** → stays on the form ("No truck with ID …").
   - **No response** → saved locally anyway; `AlertService` re-sends it later.
3. On success: asks for notification permission (Android 13+) and starts
   `AlertService`.

### 6.4 `AlertService` — alerts with the app closed

| Aspect | Behaviour |
|---|---|
| Type | Foreground service, `foregroundServiceType="dataSync"`, ongoing low-importance notification on `kachra_free_watching`, id 2001 |
| Polling | `GET /api/residents/status` every **15 s** (`POLL_INTERVAL_MS`), on its own timer, whether or not any screen is open |
| Survives | Closing the app and swiping it from Recents (it's a foreground service); being killed (`START_STICKY`); reboot (`BootReceiver`) |
| Self-healing registration | If the server answers **404 resident not found**, it re-sends the saved registration, then fetches status again |
| Alert notification | When the response carries an `alert` whose id differs from `last_alert_id`, posts a high-importance notification on `kachra_free_alerts` ("Garbage truck is coming") and saves the id, so each round alerts **once** |
| Status notification | The ongoing notification's text is updated to the server's latest status message |
| Shared state | `lastStatus` (read by `MainActivity` once a second for the UI) and `isRunning` |

`MainActivity` does **no network polling** of its own: it mirrors
`AlertService.lastStatus` every 1 s while visible. `BootReceiver` listens for
`BOOT_COMPLETED` and starts the service if `registered` is true.

No push service (e.g. Firebase) is involved; the phone polls. The trade-off is
battery use and a permanent status-bar icon, in exchange for zero accounts.

### 6.5 Storage, permissions, networking

- **SharedPreferences `resident_prefs`:** `registered`, `phone_number`,
  `truck_id`, `latitude`, `longitude`, `address`, `alert_minutes`,
  `last_alert_id`.
- **Permissions:** `ACCESS_FINE/COARSE_LOCATION` (picker only),
  `INTERNET`, `ACCESS_NETWORK_STATE` (osmdroid), `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `RECEIVE_BOOT_COMPLETED`.
- **HTTP:** `ServerApi.kt`, 5 s connect / 15 s read (search can be slow),
  sends `ngrok-skip-browser-warning: true`. JSON nulls are checked with
  `isNull()` first, because `org.json` otherwise returns the string `"null"`
  or `NaN`.
- **osmdroid setup (`OsmMap.kt`):** User-Agent = package name, tile cache in
  the app's cache folder (no storage permission), zoom buttons hidden, tiles
  scaled to screen density, credit label "© OpenStreetMap contributors".

---

## 7. Server: runtime and storage

### 7.1 Runtime

- `python server.py` → `ThreadingHTTPServer` on **0.0.0.0:8080** (reachable from
  the LAN and from ngrok). Each request runs on its own thread.
- One background thread: `routing.updater` (OSRM fetching, section 8.9).
- **Concurrency:** every read or write of the in-memory data holds `db.lock`
  (a re-entrant lock). OSRM/Nominatim calls happen *outside* the lock, so a
  slow external call never blocks other requests.
- **Routing:** a dict of `(method, path) → handler`. `/` redirects to `/admin`.
  `/admin*` and `/api/admin/*` require HTTP Basic auth. Handler errors of type
  `BadRequest` become **400**; unknown routes **404**.

### 7.2 Storage

- **`data/db.json`:** the entire state. Loaded into memory at startup,
  rewritten after every change **atomically** (written to `db.json.tmp`, then
  `os.replace`), so a crash can't leave half a file. A *corrupt* file raises
  at startup rather than being silently overwritten.
- **`data/tracks/<truck>/<YYYY-MM-DD>.jsonl`:** raw GPS track, one
  `[lat, lng, unixTime]` line per location update. Kept out of db.json because
  it grows all day. Truck IDs are sanitised for filenames (anything other than
  letters, digits, `-`, `_` → `_`). Times are full precision because they're
  compared against visit times (section 8.4).
- **`data/admin_password.txt`:** generated admin password.
- All of `data/` except `.gitkeep` is gitignored.

### 7.3 Data model (`db.json`)

```jsonc
{
  "trucks": {
    "TRUCK-1": {
      "lat": 12.97, "lng": 77.59,            // last reported position
      "timestamp": 1790000000000,            // phone's fix time (ms), as sent
      "lastSeen": 1790000000.5,              // server time of last update (s)
      "stops": [ {                           // ordered = collection order
        "id": "a1b2c3d4", "name": "Market gate", "lat": 12.97, "lng": 77.59,
        "addedBy": "driver",                 // or "admin"
        "leg":    { "toId": "…", "from": [lat,lng], "to": [lat,lng],
                    "points": [[lat,lng],…], "seconds": 240.0, "meters": 900.0 },  // OSRM road to next stop
        "driven": { "toId": "…", "from": [..], "to": [..],
                    "points": [[lat,lng],…], "date": "2026-09-26" }                  // road actually driven
      } ],
      "approach": { "computedAt": 1790000000.0, "nextIndex": 2,
                    "legs": [ { "seconds": 300.0, "meters": 1200.0, "points": [..] } ] },
      "routingError": null                   // last OSRM failure message, or null
    }
  },
  "residents": {
    "+919876543210": { "truckId": "TRUCK-1", "lat": 12.98, "lng": 77.60,
                       "address": "…", "alertMinutes": 10, "source": "app",
                       "name": "", "updatedAt": 1790000000.0 }
  },
  "runs": {
    "TRUCK-1|2026-09-26": { "truckId": "TRUCK-1", "date": "2026-09-26", "synthetic": false,
                            "visits": { "a1b2c3d4": { "arrived": 1790000100.0, "left": 1790000280.0 } } }
  },
  "alerts": {
    "+919876543210|TRUCK-1|2026-09-26": { "message": "…", "sentAt": 1790000200.0 }
  },
  "settings": { "stopRadiusMeters": 50, "secondsPerStop": 120, "routeRefreshMinutes": 2,
                "fallbackSpeedKmh": 20, "searchCountryCodes": "" }
}
```

A stop's `leg` and `driven` are only trusted while `toId` is still the next
stop's id **and** both stops' coordinates equal the stored `from`/`to`.
Reordering or moving stops therefore invalidates cached roads automatically,
with no cleanup code.

### 7.4 What happens on each location update

`POST /api/trucks/location`, all under `db.lock`:

1. Create the truck if unknown.
2. Store position, phone timestamp and `lastSeen = now`.
3. Append the point to today's track file.
4. `record_position`: update visits, capture driven roads (sections 8.3–8.4).
5. `check_alerts`: recompute every resident of this truck (section 8.8).
6. Save db.json.
7. Reply with the truck's stop list.

---

## 8. Algorithms and equations

### 8.1 Distance: haversine

Used only for *proximity* ("is the truck at this stop?", "which stop is
nearest this house?", "how far along this road?"), never as a route.

```text
φ1, φ2 = latitudes in radians,  Δφ = φ2 − φ1,  Δλ = difference in longitude (radians)
a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
d = 2 · R · asin(√a),   R = 6 371 000 m
```

### 8.2 Truck online / offline

```text
active = truck has reported at least once  AND  now − lastSeen ≤ 120 s
```

### 8.3 Visits and dwell (collection time)

On every update, for each stop *s* of the truck:

```text
if d(truck, s) ≤ stopRadiusMeters (default 50 m):
    if s not visited in today's run:  visits[s] = { arrived: now, left: now }
    visits[s].left = now                 # keeps moving forward while the truck stays
dwell(s) = left − arrived
```

### 8.4 Capturing the road actually driven

When the truck **first arrives** at stop *i > 0*, and stop *i−1* was visited
earlier in the same run:

```text
points = GPS track points with  left(i−1) < t < arrived(i)
if points is not empty:
    stops[i−1].driven = [stop(i−1)] + points + [stop(i)]
```

From then on that leg is drawn along the real roads the truck took.

### 8.5 Progress within today's round

```text
last = highest stop index visited today            (−1 if none)
at   = index of the stop within stopRadius of the truck right now, or None
```

### 8.6 Resident's collection point

```text
k = argmin over the truck's stops of d(house, stop)      (always the nearest; no distance cap)
```

The status message includes that stop's name and distance from the house.

### 8.7 History-derived values

Using only **past** days' runs (today's may be incomplete), real and synthetic:

```text
typical_dwell(s) = median( left − arrived  over past visits with left > arrived )
                   or secondsPerStop (default 120 s) if there are none

usual_time(s)    = median( time-of-day of arrived, in seconds since midnight )  →  "HH:MM"
```

History affects **only** the per-stop dwell and the "usually around HH:MM"
hint. Past *drive* times are deliberately not used.

### 8.8 Status and ETA

Decision order for a resident (truck T, collection point k):

| Check | Status |
|---|---|
| T doesn't exist | `unknown_truck` |
| T has no stops | `no_stops` |
| T not active (8.2) | `truck_offline` (with "usually around HH:MM" if known) |
| `at == k` | `at_stop`, ETA 0 |
| `k ≤ last` | `collected` |
| otherwise | `on_the_way` |

For `on_the_way`, with `n = last + 1` (the next stop the truck will reach):

```text
stopsAway = k − n

ETA_seconds = D + C

  D (drive time) = Σ over legs from the truck through stops n, n+1, …, k:
      leg 0 (truck → stop n):
          if a fresh approach exists:   approach.seconds × f(approach road)
          elif leg (n−1 → n) is cached: leg.seconds      × f(that leg's road)
          else:                         d(truck, stop n) ÷ v_fallback          ← rough
      leg j > 0 (stop i−1 → stop i):
          if cached OSRM leg:           leg.seconds
          else:                         d(stop i−1, stop i) ÷ v_fallback       ← rough

  C (collecting) = Σ typical_dwell(stop i)  for i = n … k−1

ETA_minutes = max(1, ⌈ETA_seconds ÷ 60⌉)
v_fallback  = fallbackSpeedKmh × 1000 ÷ 3600   (default 20 km/h ≈ 5.56 m/s)
```

**Fraction of road remaining, f(road):** the part of a road polyline still
ahead of the truck, measured *along the road*:

```text
m        = index of the road point nearest the truck
pieces_j = d(point_j, point_j+1)
f        = Σ_{j ≥ m} pieces_j  ÷  Σ_all pieces_j
```

This is what makes the ETA count down smoothly between route refreshes.

**Freshness:** an approach is used only if it was computed for the same next
stop (`nextIndex == n`) and is younger than `2 × routeRefreshMinutes`.

**Rough flag:** if any leg used the fallback, `etaIsRough = true` and the
message adds "Rough estimate: the route server isn't reachable right now."

**Expected path:** the road shape of every remaining leg (driven, else OSRM,
else the approach's road for leg 0), with leg 0 trimmed to start at the point
nearest the truck. If any leg has no road shape, the path is sent **empty**
rather than drawing straight lines.

**Message** examples: "Truck is about 6 min away (2 stops before yours)." /
"(your stop is next)". Every message ends with "Your collection point: NAME,
N m from your house."

### 8.9 Road routes (`routing.py`)

A background loop runs every **10 s** (`update_once`), in two parts:

**(a) Stop-to-stop roads.** For each truck, find legs with no road shape (no
valid `driven` and no valid `leg`). Starting at the first such leg, request
OSRM in chunks of **12 stops (11 legs)**, overlapping by one stop so every
leg is covered. Each returned leg is stored on its stop **only if** those two
stops are still consecutive and unmoved (the stop list may have changed while
waiting for OSRM).

**(b) Approach.** For each **active** truck that hasn't finished its round
(`n < number of stops`), fetch the road from the truck to stop *n* when:

```text
no approach yet   OR   approach.nextIndex ≠ n   OR   now − computedAt ≥ routeRefreshMinutes × 60
```

So it refreshes every 2 minutes by default, and immediately after the truck
reaches a new stop. One request per truck, shared by all its residents.

**Failure handling:** an OSRM error sets the truck's `routingError` (shown in
the admin Alerts panel) and **backs off that truck for 120 s**. Success clears
the error.

### 8.10 OSRM and Nominatim clients (`osm.py`)

- **Rate limiting:** a lock guarantees **≥ 1 s between any two requests**, as
  both public servers' policies ask. User-Agent `KachraFree/1.0 (student prototype)`.
  Timeout 15 s.
- **OSRM request:** `GET /route/v1/driving/{lng,lat;lng,lat;…}?overview=false&steps=true&geometries=geojson`.
  Each leg's `duration` (s) and `distance` (m) are used directly; its road
  shape is the joined geometry of its turn-by-turn steps, converted from
  GeoJSON `[lng, lat]` to `[lat, lng]` with consecutive duplicates removed.
  Any response other than `code == "Ok"` with at least one route raises.
- **Nominatim request:** `GET /search?format=jsonv2&limit=5&q=…[&countrycodes=…]`
  → `[{name: display_name, latitude, longitude}]`. Results are cached per
  `(lowercased query, country codes)`; the cache is cleared when it exceeds 500
  entries.

### 8.11 Alerts (`alerts.py`)

After every location update of truck T, for every resident with
`truckId == T`:

```text
status = resident_status(resident)
if status ∈ {on_the_way, at_stop}  and  etaMinutes ≤ alertMinutes:
    key = "<phone>|<truckId>|<date>"
    if key not in alerts:
        alerts[key] = { message, sentAt: now }
```

Messages: "The garbage truck is about N minutes from STOP." or "The garbage
truck is at STOP now." Because the key includes the date, a resident gets
**at most one alert per collection round**. The status endpoint attaches the
alert for the current round; the app shows it once (`last_alert_id`).

### 8.12 Demo history generator

For a truck with stops, create **14** past days (`synthetic: true`):

```text
base_dwell(s) ~ Uniform(60, 300) s          # each stop's own typical collection time
for each day d = 1 … 14 days ago:
    t = 07:00 local on that day + Uniform(−600, +600) s
    for each stop i in order:
        if i > 0:  t += (OSRM leg seconds, or 300 s if none) × Uniform(0.9, 1.5)
        dwell = base_dwell(s_i) × Uniform(0.7, 1.3)
        visit = { arrived: t, left: t + dwell };   t += dwell
```

This gives every stop a consistent but noisy typical dwell and arrival time,
so ETAs and "usually around" hints work before any real history exists.
**Clear demo history** deletes only `synthetic` runs.

### 8.13 Small rules

- **Phone normalisation:** keep only digits and `+`, so `+91 98765-43210` and
  `+919876543210` are the same resident.
- **New ids:** `secrets.token_hex(4)` (8 hex characters) for stops.
- **Admin password:** the `ADMIN_PASSWORD` environment variable if set,
  otherwise `secrets.token_urlsafe(9)` generated once into
  `data/admin_password.txt`. Compared with `hmac.compare_digest`
  (constant-time).
- **Numbers:** booleans are rejected where numbers are expected (Python
  treats `True` as `1`).

---

## 9. API reference

All request and response bodies are JSON. Errors are `{"error": "…"}`.
Status codes: **200** OK, **302** redirect, **400** bad input, **401** admin
auth needed, **404** not found / unknown truck / unknown resident, **502**
search provider failed.

### 9.1 Driver app

**`POST /api/trucks/location`**

```json
{ "truckId": "TRUCK-1", "latitude": 12.9716, "longitude": 77.5946, "timestamp": 1790000000000 }
```
→ `{"ok": true, "stops": [{"name": "Stop 1", "latitude": 12.97, "longitude": 77.59}, …]}`
Requires `truckId` and numeric coordinates (400 otherwise). Creates the truck
if new. Side effects: section 7.4.

**`POST /api/trucks/stops`**

```json
{ "truckId": "TRUCK-1", "latitude": 12.9716, "longitude": 77.5946 }
```
→ same shape as above, including the new `"Stop N"` at the end.

### 9.2 Resident app

**`POST /api/residents/register`**

```json
{ "phoneNumber": "+919876543210", "truckId": "TRUCK-1",
  "latitude": 12.9716, "longitude": 77.5946, "alertMinutes": 10, "address": "12 MG Road" }
```
→ `{"ok": true}`, or **404** `{"error": "unknown truck"}`. Upsert keyed on the
normalised phone; `address` optional. Keeps any admin-set `name`.

**`GET /api/residents/status?phone=…`**

```json
{
  "status": "on_the_way",
  "message": "Truck is about 6 min away (2 stops before yours). Your collection point: Market gate, 80 m from your house.",
  "truckId": "TRUCK-1", "truckLatitude": 12.9716, "truckLongitude": 77.5946,
  "stopName": "Market gate", "stopLatitude": 12.972, "stopLongitude": 77.595, "stopDistanceMeters": 80,
  "etaMinutes": 6, "etaIsRough": false, "stopsAway": 2, "usualTime": "07:40",
  "path": [ { "latitude": 12.9716, "longitude": 77.5946 }, … ],
  "runId": "TRUCK-1|2026-09-26",
  "alert": { "id": "+919876543210|TRUCK-1|2026-09-26", "message": "…" }
}
```
Fields that don't apply are `null` / `[]`. **404** if the phone isn't
registered (the app then re-registers). `status` values: section 8.8.

**`GET /api/places/search?query=…`**
→ `{"results": [{"name": "…", "latitude": …, "longitude": …}]}` (≤ 5, Nominatim,
limited to `searchCountryCodes` if set). Empty query → empty results.
**502** if Nominatim fails (also shown in admin Alerts for an hour).

### 9.3 Admin (HTTP Basic auth, user `admin`)

| Method + path | Body / query | Response / effect |
|---|---|---|
| `GET /admin` | — | The admin page (`/admin/admin.js`, `/admin/admin.css` likewise, `Cache-Control: no-store`) |
| `GET /` | — | 302 → `/admin` |
| `GET /api/admin/state` | — | `{alerts, trucks, residents, settings}`: trucks with stops (road points + source, typical dwell, usual time, added by), last update age, active, stops reached today, real/demo run counts, approach age, routing error; residents with truck, collection point + distance, live status message |
| `POST /api/admin/trucks` | `{id}` | Create a truck |
| `DELETE /api/admin/trucks?id=` | | Delete a truck and its stops |
| `POST /api/admin/trucks/stops` | `{truckId, stops: [{id?, name, lat, lng}]}` | Replace the stop list. Stops keeping their id keep their road data; missing ids are generated; missing names become "Stop N" |
| `POST /api/admin/trucks/demo-history` | `{truckId, action: "generate" \| "clear"}` | → `{runs: N}`. Generate needs at least one stop |
| `GET /api/admin/track?truckId=&date=` | date defaults to today | `{points: [[lat, lng], …]}` from the track file |
| `POST /api/admin/residents` | as register, plus `name` | Register/edit a resident on their behalf (same record type as the app; `source: "admin"`). 400 for unknown truck |
| `DELETE /api/admin/residents?phone=` | | Delete a resident |
| `POST /api/admin/settings` | any settings | Numeric settings must be positive (400 otherwise) |

---

## 10. Admin panel

Open `/admin` locally or through the ngrok URL; log in as `admin` with the
password printed at startup. The page re-fetches `/api/admin/state` **every
5 s** and redraws.

| Section | What it does |
|---|---|
| **Alerts** | Current problems: OSRM failures per truck (ETAs then rough), Nominatim failures within the last hour. Green "all good" otherwise |
| **Map** (Leaflet + OSM) | Trucks (orange, grey when offline, labelled), each truck's stops numbered in order (blue; the selected truck's in orange-red), roads between stops, residents (green dots, tooltip with live status). Optional grey line: the selected truck's GPS track today. Fits all points on first load |
| **Trucks** | ID (⚠ on routing error), last update, stop count, "reached stop X of Y" today, road-to-next-stop age, real/demo history days, **Edit stops**, Delete, Add truck |
| **Stop editor** | Rename, ↑↓ reorder, ✕ remove, **Add stops by clicking the map**, Save, Discard. Shows each stop's usual time, typical collecting time, whether a driver added it, and whether its road is "as driven" or "shortest route". Stops the driver adds appear live **unless** there are unsaved edits (saving replaces the whole list). **Show roads driven today**, **Generate / Clear demo history** |
| **Residents** | Everyone (app- and admin-registered) with truck, collection point + distance, alert threshold and live status. Register or edit a resident (pick the house on the map); delete |
| **Settings** | The five settings in section 11.1 |

Everything user-supplied is HTML-escaped before display. Map clicks pass
through lines (non-interactive) so stops can be added on top of roads.

---

## 11. Settings and constants

### 11.1 Server settings (editable in the admin panel)

| Setting | Default | Used in |
|---|---|---|
| `stopRadiusMeters` | 50 m | Visit detection, "at stop", progress (8.3, 8.5) |
| `secondsPerStop` | 120 s | Dwell for stops with no history (8.7) |
| `routeRefreshMinutes` | 2 min | Approach refresh interval; freshness window is 2× this (8.8, 8.9) |
| `fallbackSpeedKmh` | 20 km/h | Rough drive time when OSRM is unavailable (8.8) |
| `searchCountryCodes` | "" (any) | Narrows Nominatim search, e.g. `in` |

### 11.2 Fixed constants

| Constant | Value | Where |
|---|---|---|
| Driver GPS/upload interval | 10 s | `LocationService.UPDATE_INTERVAL_MS` |
| Network-fix suppression window | 20 s (2 × interval) | `LocationService` |
| Add-stop undo window | 20 s | driver `MainActivity.ADD_STOP_DELAY_MS` |
| Driver UI refresh | 1 s | driver `MainActivity` |
| Resident status polling | 15 s | `AlertService.POLL_INTERVAL_MS` |
| Resident UI refresh | 1 s | resident `MainActivity.UI_REFRESH_MS` |
| Truck offline after | 120 s | `eta.ACTIVE_SECONDS` |
| Routing loop | every 10 s | `routing.updater` |
| OSRM batch size | 12 stops | `routing.STATIC_CHUNK` |
| OSRM error back-off | 120 s per truck | `routing.RETRY_AFTER_ERROR_SECONDS` |
| External request spacing | ≥ 1 s | `osm._get_json` |
| External request timeout | 15 s | `osm._get_json` |
| Search cache limit | 500 entries | `osm.search` |
| Demo history | 14 days, start 07:00 ± 10 min | `eta.generate_demo_history` |
| Demo spacing without a road | 300 s | `eta.DEMO_SECONDS_BETWEEN_STOPS` |
| Admin panel refresh | 5 s | `admin.js REFRESH_MS` |
| Search error shown in Alerts for | 1 h | `server.system_alerts` |
| Picker settle delay / zoom | 400 ms / 17 | `LocationPickerScreen` |
| Truck map zoom / fit padding | 15 / 120 px | `TruckMapScreen` |
| Server port | 8080 | `server.PORT` |

---

## 12. Security and keys

- **No API keys anywhere.** OSRM, Nominatim and OpenStreetMap tiles need none.
- **Admin panel:** HTTP Basic auth, user `admin`, constant-time password
  comparison. The password is encrypted in transit over the ngrok `https://`
  URL, but not over plain `http://` on local Wi-Fi.
- **App endpoints are open** (no login), by design for the prototype: anyone
  who knows a Truck ID could post locations for it, and anyone could register
  any phone number.
- **Cleartext:** both apps set `usesCleartextTraffic="true"` only so plain
  `http://` works for emulator/LAN testing; the ngrok URL is HTTPS.
- **Input handling:** JSON bodies must be objects, numbers are type-checked,
  phone numbers are normalised; the admin page escapes all user text.
- **Secrets in git:** `data/` (db, password, tracks) and `local.properties`
  are gitignored.

---

## 13. Networking: reaching the laptop (ngrok)

A laptop on Wi-Fi only has a private address, and many ISPs use CGNAT, which
makes port-forwarding impossible. So the server is exposed through an
**ngrok** tunnel: the ngrok agent on the laptop keeps an outbound connection
to ngrok, which serves a public HTTPS address and forwards it to
`localhost:8080`. The free plan includes one **static domain** that survives
restarts, so the apps' `BASE_URL` is set once. It's currently
`https://undaunted-ditzy-botany.ngrok-free.dev` in both apps' `ServerConfig.kt`.

- Both apps send `ngrok-skip-browser-warning: true` on every request, so the
  free tier's HTML warning page doesn't replace the JSON.
- If the tunnel isn't running, ngrok answers with an "endpoint offline"
  page (`ERR_NGROK_3200`); the apps show "server unreachable" and recover by
  themselves once it's back.
- Setup: [SETUP.md](SETUP.md) section 8. Day-to-day launch: section 10.

---

## 14. Demo tools

### 14.1 Truck simulator (`server/simulate.py`)

Drives a pretend truck through its stops by sending the same location
updates the driver app sends:

```text
python simulate.py TRUCK-1          # an update every 10 s (real pace)
python simulate.py TRUCK-1 --fast   # an update every 1 s
```

- Reads the truck's stops from `data/db.json` (the truck must have stops).
- Between stops it follows the known road (driven, else OSRM); it moves in
  **40 m** steps, skipping road points closer than one step. With no known
  road for a leg it **jumps** to the next stop instead of inventing a path.
- Parks at each stop for **3 updates** (so visits and dwell get recorded).
- Posts to `127.0.0.1` rather than `localhost`, because on Windows
  `localhost` tries IPv6 first and stalls about 2 s per request.
- `--fast` records real (compressed) history, so use a test truck with it.

### 14.2 Demo history

**Generate demo history** in the admin panel (section 8.12) gives stops
realistic collection times and usual arrival times immediately.

---

## 15. Limitations and deliberate omissions

| Not included | Why / what instead |
|---|---|
| Live traffic | Every provider charges. Drive time uses OSRM's normal road time, refreshed from the truck's actual position |
| SMS alerts | Cost money (e.g. Twilio). Alerts are app notifications only, so residents without the app get none |
| Push notifications (Firebase) | Avoided to need no accounts. `AlertService` polls instead: works with the app closed, but uses more battery and shows a permanent status-bar icon |
| Automatic truck selection | Residents enter their Truck ID; their stop is the nearest on that truck |
| Authentication for apps | Open endpoints (section 12) |
| Scale | One JSON file rewritten on each change, public OSRM/Nominatim servers: fine for a few trucks and residents, not for a city |
| Aggressive phone brands | Some (Xiaomi, Oppo, Vivo, …) kill background services unless the app's battery setting is "Unrestricted" |

---

## 16. How it was tested

- **Both apps** build with `gradlew assembleDebug` with no compiler warnings.
  They have not been run on a real phone from this environment (see
  [TODO_FOR_YOU.md](TODO_FOR_YOU.md)).
- **Server logic** was tested end to end with a scripted scenario (fake and
  real OSRM): registration (including unknown truck and missing fields), ETA
  counting down as the truck moves, driven-road capture, `at_stop` and
  `collected`, one alert per round, demo history generate/clear, driver
  add-stop, GPS track retrieval, OSRM failure → Alerts panel + rough
  estimate, and admin auth (401 without password).
- **Real services:** a live OSRM route and Nominatim search were exercised
  through the server code, including a simulated truck driving real
  Bengaluru roads with a non-rough, counting-down ETA.
- The admin JavaScript was syntax-checked with `node --check`; all Python
  files compile.
