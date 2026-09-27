# Kachra Free — Full Technical Explainer

A complete description of what this repository does and how: every feature,
every calculation, every endpoint, every dependency and setting. [CLAUDE.md](../CLAUDE.md)
(the product spec, coding conventions and decisions) says *what* the system should do; this file says exactly how the current code
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
15. [Limitations](#15-limitations)
16. [Checking your changes](#16-checking-your-changes)

---

## 1. Overview

**The promise:** *know when your garbage truck is coming.*

A driver's phone reports the truck's GPS position every 2 seconds, and the
driver presses **Garbage collected** at each stop. Each truck
has an ordered list of **stops**: collection points where it parks and nearby
residents bring their garbage out (the truck never goes house to house). A
resident registers with the **Truck ID** of the truck serving their area and
their house location, and is served at that truck's stop nearest their house.
The server works out when the truck will reach that stop and alerts the
resident once, when it's within the number of minutes they chose.

```text
 driver-app ── POST /api/trucks/location every 2 s ──▶ ┌────────────────────────────────┐
            ◀─ this truck's stops (two-way sync) ──────│ server/  (Python stdlib)       │
            ── POST /api/trucks/collected, /stops ──▶ │  collected stops → history     │◀─▶ OSRM
                                                      │  roads driven → GPS tracks     │    (road routes,
 resident-app                                         │  ETA = road drive time         │     drive times)
   AlertService ── GET /api/residents/status / 2 s ──▶ │      + typical collecting time │◀─▶ Nominatim
   MainActivity ── POST /api/residents/register ─────▶ │  threshold alerts (once/round) │    (place search)
   Picker       ── GET /api/places/search, suggest ──▶ │  data/db.json + data/tracks/   │◀─▶ Photon
                                                      │                                │    (suggestions)
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
├── CLAUDE.md                      Product spec, coding conventions, decisions (gitignored)
├── docs/
│   ├── EXPLAINER.md               This file
│   ├── SETUP.md                   Installing tools, building, running a demo, emulator
│   └── LOG.md                     Development log: what changed, was removed or fixed, and why
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
│           ├── HomeScreen.kt            Main page: map + ETA card + server pill + settings
│           ├── ServerIndicator.kt       "Server online / offline" pill
│           ├── LocationPickerScreen.kt  Drop-a-pin map UI
│           └── OsmMap.kt                Shared osmdroid setup, markers, credit
└── server/
    ├── server.py                  HTTP server, routing, auth, every endpoint
    ├── db.py                      JSON "database", lock, GPS track files
    ├── eta.py                     Collected stops, history, roads, ETA, status
    ├── routing.py                 Background thread fetching OSRM routes
    ├── osm.py                     OSRM + Nominatim + Photon clients (rate-limited)
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
| **Nominatim**, `nominatim.openstreetmap.org` | server (`osm.py`) | Place/address search for the resident picker (the **Go** button) | Same 1 s spacing, results cached |
| **Photon**, `photon.komoot.io` | server (`osm.py`) | Search suggestions while typing (Nominatim's policy forbids search-as-you-type; Photon is the OSM search built for it) | Same 1 s spacing, results cached |
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
| **Visit** | A stop's record in a run. Real run: `collectedAt`, when the driver pressed **Garbage collected** there. Demo run: `arrived` and `left`; `left − arrived` = **stoppage time**. |
| **Stop status (today)** | `collected` (button pressed), `skipped` (not collected, but a later stop was), `next` (the one after the last collected stop) or `pending`. |
| **Resident** | Keyed by a **username** they choose (3–30 letters, digits, `.` `_` `-`; not case-sensitive). The same username registering again, on the same phone or another, is the same resident, so there are never duplicates. Has a Truck ID, house location and alert threshold (1–120 min). |
| **Collection point** | For a resident: their truck's stop nearest their house. |
| **Approach** | The road route from the truck's current position to its next stop, refreshed periodically. |
| **Synthetic run** | Made-up past history for demos, flagged `synthetic: true`. |

---

## 5. driver-app

**Purpose:** report the truck's location with no interaction while driving,
and let the driver add collection stops (CLAUDE.md section 1).

### 5.1 Screen (`MainScreen.kt`)

- **Server pill** next to the title: "Server online" / "Server offline". Not tracking: `GET /api/ping` every **2 s**. Tracking: whether the last location upload got through (no extra requests).
- **Truck ID** text field (locked while sharing).
- **Location sharing: on/off**, current position with seconds since the fix,
  and **server status** (`connecting…` / `connected, last sent Ns ago` /
  `unreachable, last sent Ns ago`).
- **Start / Stop sharing location** button. Start first asks the server
  whether the Truck ID exists (`GET /api/trucks?truckId=…`, button shows
  "Checking Truck ID…"): unknown → "No truck with ID X on the server. Ask
  the admin to add it."; unreachable → "Can't reach the server to check the
  Truck ID"; only a known ID goes on to permissions and tracking.
- While sharing:
  - **Next stop**: "Next stop: 3. Janpath Market, 240 m away" (distance from
    the phone's own position), or "All stops done for today".
  - **Garbage collected at N. Name**: a big green button that appears when
    the phone is within the **collect-button radius** (admin setting
    `stopRadiusMeters`, default **30 m**) of a stop that isn't collected yet (the
    closest one; usually the next stop, another one if the driver skipped
    ahead). Pressing it → `POST /api/trucks/collected`.
  - **Collection stops** list with each stop's status: `✓ collected`,
    `⚠ skipped`, `← next`.
  - **Add stop here**: starts a **20-second countdown** with **Undo**; only
    after 20 s is the stop sent.

`MainActivity` redraws this once a second (`uiHandler`, 1000 ms) by reading
`LocationService`'s in-memory state, so the screen always shows the service's
real state rather than a saved flag.

### 5.2 `LocationService` (foreground service)

| Aspect | Behaviour |
|---|---|
| Type | Foreground service, `foregroundServiceType="location"`, ongoing notification on channel `kachra_free_tracking` (low importance), id 1001. Tapping it opens the app |
| Fix interval | `UPDATE_INTERVAL_MS = 2 000` ms, `minDistance = 0` m, so a parked truck still reports |
| Providers | Both `GPS_PROVIDER` and `NETWORK_PROVIDER` |
| Duplicate suppression | A network fix is dropped if a GPS fix arrived within the last 2 × 2 s |
| Upload | Each accepted fix → `POST /api/trucks/location` on a background thread. A fix arriving while the previous upload is still on its way is skipped, so a slow network can't pile up requests. Failures are dropped; the next fix is 2 s later |
| Stop sync | The upload's response contains the truck's stops (id, name, position, today's status) → stored in `routeStops` for the screen |
| Stop sharing | Removes the location updates and sends `POST /api/trucks/stop-sharing`, so the truck is offline at once |
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
location upload (≤ 2 s), so there's no separate sync request.

### 5.4 Storage, permissions, networking

- **SharedPreferences `driver_prefs`:** `truck_id`, `tracking` (whether to
  resume after a forced restart).
- **Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`,
  `INTERNET`.
- **HTTP:** `ServerApi.kt`, 5 s connect / 5 s read timeouts, sends
  `ngrok-skip-browser-warning: true`.
- **Only known Truck IDs:** trucks are added in the admin panel; the
  server answers 404 to any driver request for an unknown ID.

---

## 6. resident-app

**Purpose:** register once, see the truck, its road and the ETA, and get an
alert even when the app is closed (CLAUDE.md sections 2, 3, 5).

### 6.1 Screens

| Screen | Contents |
|---|---|
| `RegisterScreen` | Server pill, **username**, **Truck ID**, house location (opens the picker), alert time as a number of minutes (**1–120**), Register (or Save/Cancel as the Settings page, where the username is locked) |
| `HomeScreen` (main page once registered) | Full-screen osmdroid map. **Top:** server pill (online/offline) and a ⚙ settings button (opens `RegisterScreen` as **Settings**; Back or Cancel restores the saved values). **Bottom:** ETA card: big number ("8 min", "~8" when rough, "Now", "Done", "--"), a coloured state pill (On the way / At your stop / Collected / Skipped / Not on the road / Needs attention), one line ("2 stops before yours", or the usual time when the truck is offline) and the collection point ("Stop 3 · Market gate · 80 m from home"). Keeps showing the last update while the server is unreachable, saying so. A button re-frames the map |
| Map markers (all drawn from vector icons, `OsmMap.kt`) | **Truck:** white truck on an orange (`#F76707`) circle, no label. **Stops:** a white dustbin on a dark-grey circle with a **number bubble** (collection order) at the top right and a **status bubble** at the top left: green ✓ collected today, yellow ! skipped today; the resident's own stop is green (`#2F9E44`) and larger. **House:** red (`#E03131`) map pin. **Route:** blue (`#1C7ED6`) line, the road to their stop (the round's planned road up to their stop while the truck is offline). Frames house + truck + their stop + route **once**, then leaves the map where the user puts it |
| `LocationPickerScreen` | "Drop a pin" picker (below) |

### 6.2 House picker (`LocationPickerActivity` + `LocationPickerScreen`)

Swiggy/Zomato-style: the pin is **fixed at the centre of the map** and the
map moves underneath it. Layout top to bottom: the map (search box floating
over its top, my-location button at its bottom-right corner), then the
address panel *below* the map, so nothing overlaps. Icons are vector
drawables (`res/drawable/ic_pin.xml`, `ic_my_location.xml`).

- **Start position:** the previously saved house, or India's approximate
  centre (20.5937, 78.9629). Zoom 17.
- **Settle detection:** osmdroid `DelayedMapListener` fires 400 ms after
  panning/zooming stops → the centre becomes the picked point → Android
  `Geocoder` reverse-geocodes it into an address (on a background thread).
  The panel shows the address ("Finding the address…" meanwhile, or "No
  street address found for this spot") **and** the latitude/longitude. A
  numbered lookup makes sure a slow, older answer can't replace a newer one.
- **Suggestions while typing:** once typing pauses for **500 ms** with at
  least **3** letters → `GET /api/places/suggest` (server → Photon), biased
  to places near the map's centre → up to 5 suggestions under the box.
  Answers for text that has changed since are ignored.
- **Search (Go):** `GET /api/places/search` (server → Nominatim) → up to 5
  results; tapping any result animates there (600 ms). On failure it pings
  the server to say which is down: "Can't reach the Kachra Free server…" or
  "Location search isn't answering…". Empty → "No matches for that search".
- **Use my location** (crosshair button): asks for location permission if needed, uses the last
  known fix if available, otherwise requests **one** fix and unregisters
  immediately. The resident is never tracked.
- **Confirm** returns latitude, longitude and address (if found) to
  `MainActivity`.

### 6.3 Registration

1. Client validation: username (3–30 letters, digits, `.` `_` `-`), Truck ID,
   house, and alert minutes (a whole number, 1–120) must be set.
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
| Polling | `GET /api/residents/status` every **2 s** (`POLL_INTERVAL_MS`), on its own timer, whether or not any screen is open. A poll is skipped while the previous one is still waiting |
| Survives | Closing the app and swiping it from Recents (it's a foreground service); being killed (`START_STICKY`); reboot (`BootReceiver`) |
| Self-healing registration | If the server answers **404 resident not found**, it re-sends the saved registration, then fetches status again |
| Alert notification | When the response carries an `alert` whose id differs from `last_alert_id`, posts a high-importance notification on `kachra_free_alerts` ("Garbage truck is coming") and saves the id, so each round alerts **once** |
| Status notification | The ongoing notification shows one short line ("Truck about 8 min away · 2 stops before yours", "The truck is at your stop now!", "Garbage collected at your stop today ✓", "The truck skipped your stop today"…), with the full server message when expanded. Redrawn only when the line changes. Tapping it (or the alert notification) opens the app |
| Shared state | `lastStatus` (read by `MainActivity` once a second for the UI) and `isRunning` |

`MainActivity` doesn't poll the truck status itself: it mirrors
`AlertService.lastStatus` every 1 s while visible. The **server indicator**
uses `AlertService.lastPollOk` while the service runs; before registering
(no service yet) the screen calls `GET /api/ping` every **2 s** itself, for the
(grey "Connecting…", green "Server online", red "Server offline") on
the register and home screens. That works before registering too. `BootReceiver` listens for
`BOOT_COMPLETED` and starts the service if `registered` is true.

Alerts come from this polling: the app asks the server, so no push service
or account is needed. It costs some battery and keeps a permanent
status-bar notification.

### 6.5 Storage, permissions, networking

- **SharedPreferences `resident_prefs`:** `registered`, `username`
  (lowercased; checked by `AppPrefs.usernameProblem()` with the server's
  rules; shown but locked on the Settings page),
  `truck_id`, `latitude`, `longitude`, `address`, `alert_minutes`,
  `last_alert_id`.
- **Permissions:** `ACCESS_FINE/COARSE_LOCATION` (picker only),
  `INTERNET`, `ACCESS_NETWORK_STATE` (osmdroid), `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `RECEIVE_BOOT_COMPLETED`.
- **HTTP:** `ServerApi.kt`, 5 s connect / 20 s read (search can be slow),
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
  compared against the times stops were collected (section 8.4).
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
      "sharing": true,                       // false after the driver pressed Stop
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
                       "address": "…", "alertMinutes": 10,
                       "name": "", "updatedAt": 1790000000.0 }
  },
  "runs": {
    "TRUCK-1|2026-09-26": { "truckId": "TRUCK-1", "date": "2026-09-26", "synthetic": false,
                            "visits": { "a1b2c3d4": { "collectedAt": 1790000280.0 } } },
    "TRUCK-1|2026-09-25": { "truckId": "TRUCK-1", "date": "2026-09-25", "synthetic": true,
                            "visits": { "a1b2c3d4": { "arrived": 1789913700.0, "left": 1789913880.0 } } }
  },
  "alerts": {
    "+919876543210|TRUCK-1|2026-09-26": { "message": "…", "sentAt": 1790000200.0 }
  },
  "settings": { "stopRadiusMeters": 30, "secondsPerStop": 120, "routeRefreshMinutes": 2,
                "fallbackSpeedKmh": 20 }
}
```

A stop's `leg` and `driven` are only trusted while `toId` is still the next
stop's id **and** both stops' coordinates equal the stored `from`/`to`.
Reordering or moving stops therefore invalidates cached roads automatically,
with no cleanup code.

### 7.4 What happens on each location update

`POST /api/trucks/location`, all under `db.lock`:

1. 404 if the truck is unknown.
2. Store position, phone timestamp, `lastSeen = now` and `sharing = true`.
3. Append the point to today's track file.
4. `check_alerts`: recompute every resident of this truck (section 8.8).
5. Save db.json.
6. Reply with the truck's stops and today's status of each.

`POST /api/trucks/collected` marks one stop collected (section 8.3), captures
the road driven since the previous stop (8.4), runs `check_alerts`, saves and
replies with the stops the same way.

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
active = the truck has reported at least once
         AND sharing                       (the driver hasn't pressed Stop)
         AND now − lastSeen ≤ 10 s         (ACTIVE_SECONDS)
```

The driver app sends a location every 2 s, so 10 s of silence means
something is wrong: no internet, no GPS fix, the phone is off, or the app
was killed. Pressing **Stop sharing location** makes the truck offline at
once (`POST /api/trucks/stop-sharing`).

### 8.3 Collected and skipped stops

The driver decides when a stop is done: **Garbage collected** (shown within
the collect-button radius, 30 m by default) → `POST /api/trucks/collected` →

```text
today's run.visits[stop] = { collectedAt: now }
```

Nothing is guessed from where the truck stands or for how long. Today's
status of each stop *i*:

```text
last = highest stop index collected today (−1 if none)
collected  if stop i has a visit today
skipped    if not, and i < last          (a later stop was collected first)
next       if i == last + 1
pending    otherwise
```

### 8.4 Capturing the road actually driven

When stop *i > 0* is collected and stop *i−1* was collected earlier in the
same run:

```text
points = GPS track points with  collectedAt(i−1) < t < collectedAt(i)
if points is not empty:
    stops[i−1].driven = [stop(i−1)] + points + [stop(i)]
```

From then on that leg is drawn along the real roads the truck took.

### 8.5 Progress within today's round

```text
last = highest stop index collected today            (−1 if none)
at   = index of the stop within stopRadius of the truck right now, or None
```

### 8.6 Resident's collection point

```text
k = argmin over the truck's stops of d(house, stop)      (always the nearest; no distance cap)
```

The status message includes that stop's name and distance from the house.

### 8.7 History-derived values

Using only **past** days' runs (today's may be incomplete):

```text
typical_dwell(s) = median stoppage time ( left − arrived ) over past visits that
                   have arrival and departure times (demo history, for now)
                   or secondsPerStop (default 120 s) if there are none

usual_time(s)    = median time of day the stop was reached (arrived, or
                   collectedAt for real rounds), in seconds since midnight  →  "HH:MM"
```

Real rounds record only when a stop was collected, not how long the truck
stood there, so stoppage times come from demo history for now.
History affects **only** the per-stop stoppage time and the "usually around HH:MM"
hint. Drive time always comes from the road route (OSRM), never from history.

### 8.8 Status and ETA

Decision order for a resident (truck T, collection point k):

| Check | Status |
|---|---|
| T doesn't exist | `unknown_truck` |
| T has no stops | `no_stops` |
| stop k collected today | `collected` |
| stop k skipped today | `skipped` |
| T not active (8.2) | `truck_offline` (with "usually around HH:MM" if known) |
| `at == k` | `at_stop`, ETA 0 |
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

  C (stoppages)  = Σ typical_dwell(stop i)  for i = n … k−1
                   (a stop's time only drops out once the driver marks it
                   collected, so a truck still parked there keeps it counted)

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
the admin Alerts panel) and **backs off that truck for 30 s**. The error
clears on the next success, straight away when the truck's stops are saved
or a driver adds a stop (those also end the back-off, so the new stops are
routed within 10 s), and at every server start (a saved error describes the
previous run).

**At server start** everything is read back from `db.json`: trucks and their
last positions, stops, cached OSRM roads and driven roads, history,
residents and settings. The routing loop runs once immediately, so any leg
still missing a road is fetched within seconds.

### 8.10 OSRM, Nominatim and Photon clients (`osm.py`)

- **Rate limiting:** a lock guarantees **≥ 1 s between any two requests**, as
  the public servers' policies ask. User-Agent `KachraFree/1.0 (student prototype)`.
  Timeout 15 s.
- **OSRM request:** `GET /route/v1/driving/{lng,lat;lng,lat;…}?overview=false&steps=true&geometries=geojson`.
  Each leg's `duration` (s) and `distance` (m) are used directly; its road
  shape is the joined geometry of its turn-by-turn steps, converted from
  GeoJSON `[lng, lat]` to `[lat, lng]` with consecutive duplicates removed.
  Any response other than `code == "Ok"` with at least one route raises.
- **Nominatim request:** `GET /search?format=jsonv2&limit=5&q=…`
  → `[{name: display_name, latitude, longitude}]`. Results are cached per
  lowercased query; the cache is cleared when it exceeds 500
  entries.
- **Photon request (suggestions):** `GET /api/?q=…&limit=5[&lat=…&lon=…]`.
  The map centre is rounded to 0.1° (about 10 km) so small map moves reuse
  the cache. Each result's name is joined from its parts (name, house number + street, district,
  city, state, country), skipping empty and repeated parts.

### 8.11 Alerts (`alerts.py`)

After every location update of truck T, for every resident with
`truckId == T`:

```text
status = resident_status(resident)
if status ∈ {on_the_way, at_stop}  and  etaMinutes ≤ alertMinutes:
    key = "<username>|<truckId>|<date>"
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
        stoppage = base_dwell(s_i) × Uniform(0.7, 1.3)
        visit = { arrived: t, left: t + stoppage };   t += stoppage
        # arrival time, departure time; departure − arrival = stoppage time
```

This gives every stop a consistent but noisy typical dwell and arrival time,
so ETAs and "usually around" hints work before any real history exists.
**Clear demo history** deletes only `synthetic` runs.

### 8.13 Small rules

- **Usernames:** trimmed and lowercased; 3–30 characters of letters,
  digits, `.`, `_`, `-` (400 otherwise).
- **New ids:** `secrets.token_hex(4)` (8 hex characters) for stops.
- **Admin password:** `secrets.token_urlsafe(9)` generated on the first run
  into `data/admin_password.txt` (edit the file to change it). Compared with `hmac.compare_digest`
  (constant-time).
- **Numbers:** booleans are rejected where numbers are expected (Python
  treats `True` as `1`).
- **Positions:** every latitude must be −90…90 and longitude −180…180 (400
  otherwise). The admin map wraps clicks on its repeated side copies of the
  world back into that range, and does the same to stops when saving them.

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
Requires `truckId` and numeric coordinates (400 otherwise); **404** for an
unknown truck. Side effects: section 7.4.

**`GET /api/trucks?truckId=…`**
→ `{"ok": true, "stops": [...]}`, or **404** for an unknown truck. The driver
app checks its Truck ID with this before it starts tracking.

**`POST /api/trucks/stops`**

```json
{ "truckId": "TRUCK-1", "latitude": 12.9716, "longitude": 77.5946 }
```
→ same shape as above, including the new `"Stop N"` at the end (404 for an unknown truck).

Every driver response also carries `collectRadiusMeters` (the admin
`stopRadiusMeters` setting). In it, each stop is
`{"id", "name", "latitude", "longitude", "status"}` with `status` =
`collected` / `skipped` / `next` / `pending` (section 8.3).

**`POST /api/trucks/collected`** `{"truckId", "stopId"}` → the stops, as
above. The driver pressed **Garbage collected** at that stop. 404 for an
unknown truck, 400 for a stop that isn't on it.

**`POST /api/trucks/stop-sharing`** `{"truckId"}` → `{"ok": true}`. The
driver pressed **Stop sharing location**: the truck is offline at once.

### 9.2 Resident app

**`POST /api/residents/register`**

```json
{ "username": "asha", "truckId": "TRUCK-1",
  "latitude": 12.9716, "longitude": 77.5946, "alertMinutes": 10, "address": "12 MG Road" }
```
→ `{"ok": true}`, or **404** `{"error": "unknown truck"}`. Upsert keyed on the
username (so the same username never makes a duplicate); `address` optional;
`alertMinutes` must be 1–120 (400 otherwise).

**`GET /api/residents/status?username=…`**

```json
{
  "status": "on_the_way",
  "message": "Truck is about 6 min away (2 stops before yours). Your collection point: Market gate, 80 m from your house.",
  "truckId": "TRUCK-1", "truckLatitude": 12.9716, "truckLongitude": 77.5946,
  "stopName": "Market gate", "stopLatitude": 12.972, "stopLongitude": 77.595, "stopDistanceMeters": 80,
  "etaMinutes": 6, "etaIsRough": false, "stopsAway": 2, "usualTime": "07:40",
  "path": [ { "latitude": 12.9716, "longitude": 77.5946 }, … ],
  "runId": "TRUCK-1|2026-09-26",
  "stops": [ { "name": "Stop 1", "latitude": 12.97, "longitude": 77.59, "status": "collected" }, … ],
  "stopIndex": 2,
  "alert": { "id": "asha|TRUCK-1|2026-09-26", "message": "…" }
}
```
Fields that don't apply are `null` / `[]`. **404** if the username isn't
registered (the app then re-registers). `status` values: section 8.8
(including `skipped`). Each stop's `status` is today's (section 8.3).

**`GET /api/places/search?query=…`**
→ `{"results": [{"name": "…", "latitude": …, "longitude": …}]}` (≤ 5, Nominatim).
Empty query → empty results.
**502** if Nominatim fails (also shown in admin Alerts for an hour).

**`GET /api/places/suggest?query=…&lat=…&lng=…`**
→ same shape (≤ 5, Photon). `lat`/`lng` (optional) put nearby places first.
Fewer than 3 characters → empty results. **502** if Photon fails (also in
admin Alerts).

**`GET /api/ping`** → `{"ok": true}`. The resident app's server indicator.

### 9.3 Admin (HTTP Basic auth, user `admin`)

| Method + path | Body / query | Response / effect |
|---|---|---|
| `GET /admin` | — | The admin page (`/admin/admin.js`, `/admin/admin.css` likewise, `Cache-Control: no-store`) |
| `GET /` | — | 302 → `/admin` |
| `GET /api/admin/state` | — | `{alerts, trucks, residents, settings}`: trucks with stops (road points + source, typical stoppage, usual time, added by, today's status), last update age, active, stops collected today, real/demo run counts, approach age, routing error; residents with truck, collection point + distance, live status message |
| `POST /api/admin/trucks` | `{id}` | Create a truck |
| `DELETE /api/admin/trucks?id=` | | Delete a truck and its stops |
| `POST /api/admin/trucks/stops` | `{truckId, stops: [{id?, name, lat, lng}]}` | Replace the stop list. Stops keeping their id keep their road data; missing ids are generated; missing names become "Stop N" |
| `POST /api/admin/trucks/demo-history` | `{truckId, action: "generate" \| "clear"}` | → `{runs: N}`. Generate needs at least one stop |
| `GET /api/admin/track?truckId=&date=` | date defaults to today | `{points: [[lat, lng], …]}` from the track file |
| `GET /api/admin/history?truckId=` | | `{truckId, stops: [{name, usualTime, typicalDwellSeconds}], days: [{date, synthetic, today, visits: [{collectedAt} (real) or {arrived, left} (demo) or null, per current stop]}]}`, newest day first. 400 for an unknown truck |
| `POST /api/admin/residents` | as register (with an existing `username`) | **Edit** a resident → `{username}`. Residents register only in the app: an unknown username is refused (400), as is an unknown truck |
| `DELETE /api/admin/residents?username=` | | Delete a resident |
| `POST /api/admin/settings` | any settings | Numeric settings must be positive (400 otherwise) |

---

## 10. Admin panel

Open `/admin` locally or through the ngrok URL; log in as `admin` with the
password printed at startup. The page re-fetches `/api/admin/state` **every
2 s** and redraws.

| Section | What it does |
|---|---|
| **Alerts** | Current problems: OSRM failures per truck (ETAs then rough), Nominatim/Photon failures within the last hour. Green "all good" otherwise |
| **Map** (Leaflet + OSM) | **Each truck has its own colour** (8-colour palette, by truck order), used for its truck icon (inline SVG truck on a coloured circle, label "ID · 12s ago" underneath; grey when offline; always drawn above stops), its stops and its roads between stops. **Stops:** a white bin on a circle in the truck's colour, with a number bubble (collection order) at the top right and a status bubble at the top left: green ✓ collected today, yellow ! skipped today (the open truck's stops are outlined). **Residents:** a white person symbol on a dark circle (tooltip with live status). Optional grey line: the open truck's GPS track today. On first load it frames **all trucks** (their stops if none has reported yet). Only what's on screen (plus a 20 % margin) is drawn, redrawn after every pan/zoom |
| **Trucks** | Heading shows **N online · M offline**. Per truck: ID (⚠ on routing error), a green **● Online** / grey **● Offline** pill (online = sharing, with a location within the last 10 s; offline straight away when the driver presses Stop, or after 10 s without a location for any other reason) with "last location 12s ago" (or "never sent a location"), stop count, "collected X of Y" today, road-to-next-stop age, real/demo history days, **Edit stops**, Delete, Add truck. **Clicking a truck's ID** opens its collection history (below) |
| **Collection history** | One row per day (newest first, tagged *today* / *real* / *demo*), one column per stop. Demo days: arrival → departure and "stopped N min". Real days: "✓ HH:MM collected". Otherwise "⚠ skipped" (a later stop was collected) or "—". Then "stops collected X of Y" and the round's first – last time with its total length. A first **Usually** row shows each stop's usual arrival time and typical stoppage. Refreshes with the page every 2 s, so today's round fills in live |
| **Stop editor** | Rename, ↑↓ reorder, ✕ remove, **Add stops by clicking the map**, Save, Discard. Shows each stop's usual time, typical collecting time, whether a driver added it, and whether its road is "as driven" or "shortest route". Stops the driver adds appear live **unless** there are unsaved edits (saving replaces the whole list). **Show roads driven today**, **Generate / Clear demo history** |
| **Residents** | Everyone registered in the app, with truck, collection point + distance, alert threshold and live status. **Edit** opens a form (truck, address, house, which can be picked on the map, alert threshold); **Delete**. No adding: residents register themselves in the app |
| **Settings** | The four settings in section 11.1 |

Everything user-supplied is HTML-escaped before display. Map clicks pass
through lines (non-interactive) so stops can be added on top of roads.

---

## 11. Settings and constants

### 11.1 Server settings (editable in the admin panel)

| Setting | Default | Used in |
|---|---|---|
| `stopRadiusMeters` | 30 m | **Collect-button radius:** within it, the driver app shows **Garbage collected** (sent to the app with every stop list as `collectRadiusMeters`), and residents see "the truck is at your stop" (8.5). It never marks a stop collected by itself |
| `secondsPerStop` | 120 s | Stoppage time for stops with no history (8.7) |
| `routeRefreshMinutes` | 2 min | Approach refresh interval; freshness window is 2× this (8.8, 8.9) |
| `fallbackSpeedKmh` | 20 km/h | Rough drive time when OSRM is unavailable (8.8) |

### 11.2 Fixed constants

| Constant | Value | Where |
|---|---|---|
| Driver GPS/upload interval | 2 s | `LocationService.UPDATE_INTERVAL_MS` |
| Server pill ping (apps, when not otherwise talking to the server) | 2 s | `PING_INTERVAL_MS` |
| Network-fix suppression window | 20 s (2 × interval) | `LocationService` |
| Add-stop undo window | 20 s | driver `MainActivity.ADD_STOP_DELAY_MS` |
| Driver UI refresh | 1 s | driver `MainActivity` |
| Resident status polling | 2 s | `AlertService.POLL_INTERVAL_MS` |
| Resident UI refresh | 1 s | resident `MainActivity.UI_REFRESH_MS` |
| Truck offline after | 10 s without a location (or at once on Stop) | `eta.ACTIVE_SECONDS` |
| Routing loop | at start, then every 10 s | `routing.updater` |
| OSRM batch size | 12 stops | `routing.STATIC_CHUNK` |
| OSRM error back-off | 30 s per truck (cleared early when its stops change) | `routing.RETRY_AFTER_ERROR_SECONDS` |
| External request spacing | ≥ 1 s | `osm._get_json` |
| External request timeout | 15 s | `osm._get_json` |
| Search cache limit | 500 entries | `osm.search` |
| Demo history | 14 days, start 07:00 ± 10 min | `eta.generate_demo_history` |
| Demo spacing without a road | 300 s | `eta.DEMO_SECONDS_BETWEEN_STOPS` |
| Admin panel refresh | 2 s | `admin.js REFRESH_MS` |
| Search error shown in Alerts for | 1 h | `server.system_alerts` |
| Picker settle delay / zoom | 400 ms / 17 | `LocationPickerScreen` |
| Home map zoom / fit padding | 16 / 160 px | `HomeScreen` |
| Server port | 8080 | `server.PORT` |

---

## 12. Security and keys

- **No API keys anywhere.** OSRM, Nominatim, Photon and OpenStreetMap tiles need none.
- **Admin panel:** HTTP Basic auth, user `admin`, constant-time password
  comparison. The password is encrypted in transit over the ngrok `https://`
  URL, but not over plain `http://` on local Wi-Fi.
- **App endpoints are open** (no login), by design for the prototype: anyone
  who knows a Truck ID could post locations for it, and anyone could register
  a resident.
- **Cleartext:** both apps set `usesCleartextTraffic="true"` only so plain
  `http://` works for emulator/LAN testing; the ngrok URL is HTTPS.
- **Input handling:** JSON bodies must be objects, numbers are type-checked,
  usernames are validated; the admin page escapes all user text.
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

Drives a pretend truck through its stops by sending the same requests the
driver app sends: a location every 2 s, **Garbage collected** at each stop,
and **Stop sharing** at the end:

```text
python simulate.py TRUCK-1          # real pace: 8 m every 2 s (~15 km/h)
python simulate.py TRUCK-1 --fast   # 80 m every 2 s
```

- Reads the truck's stops from `data/db.json` (the truck must have stops).
- Between stops it follows the known road (driven, else OSRM), skipping
  road points closer than one step. With no known
  road for a leg it **jumps** to the next stop instead of inventing a path.
- Parks at each stop for **10 updates**, then presses **Garbage collected**.
- Posts to `127.0.0.1` rather than `localhost`, because on Windows
  `localhost` tries IPv6 first and stalls about 2 s per request.
- It records a real round for today, so use a test truck with it (not the one you'll demo that day).

### 14.2 Demo history

**Generate demo history** in the admin panel (section 8.12) gives stops
realistic collection times and usual arrival times immediately.

### 14.3 What demo data you need to provide

Only **a truck and its stops** have to be entered by hand. Everything else
is made up or fetched by the system.

**Entered by you (admin panel):**

1. **A truck:** type any ID, e.g. `TRUCK-1`, and click **Add truck**.
2. **Its stops:** open **Edit stops**, click **Add stops by clicking the
   map**, click 4–8 points in collection order, then **Save stops**.
   - Put them on real streets, roughly 200–800 m apart. A familiar area
     (around a college or home) makes the demo convincing.
   - The order is the route the truck drives.

**Made up or fetched for you:**

| Data | Where it comes from | What you do |
|---|---|---|
| Road between stops | OSRM, fetched automatically within about 10 s of saving (section 8.9) | Nothing (needs internet) |
| Stoppage time per stop, "usually there around 07:40" | **Generate demo history**: 14 fake past days with each stop's arrival and departure times (each stop with its own typical 1–5 min stop) | Click it once, *after* the roads appear on the map, so the fake times use real drive times |
| Truck's live position | `python simulate.py TRUCK-1 --fast` (14.1), or the real driver app | Run one of them |
| Residents | The resident app, or the admin form's **Pick house on map** | Add 2–3, with houses near different stops so they get different ETAs and alerts |

**Suggested demo setup:**

- 1 truck with 5–6 stops, demo history generated.
- 3 residents: one near stop 2, one near stop 4 and one near the last stop,
  with different alert times (5, 10 and 15 min).
- Run the simulator (or drive with the driver app, pressing **Garbage
  collected** at each stop) and watch the ETAs count down, stops get a ✓,
  and alerts fire one after another.

**Cautions:**

- A round (simulated or real) counts as that truck's round for the day:
  its stops stay collected until midnight, and each resident gets one alert
  per truck per day. Rehearse with a separate truck like `REHEARSAL-1`.
- **Clear demo history** removes only the fake days. Real history from the
  driver app or the simulator stays. To start completely fresh, stop the
  server and delete `server/data/db.json`.

---

## 15. Limitations

| Limitation | What it means |
|---|---|
| Normal road times | Drive times are OSRM's usual road times, refreshed from the truck's real position; there's no live traffic data |
| Alerts need the app | Alerts are app notifications, so a resident needs the resident app installed and registered |
| Background polling | The resident app keeps a status-bar notification and checks the server every 2 s, which uses some battery and data |
| Request volume | The apps make one request every 2 s each (driver: its location; resident: its status). Through ngrok's free plan, which has a monthly request limit, long sessions can use it up. The rates are single constants (section 11.2) |
| Open app endpoints | The apps don't log in (section 12): anyone who knows a Truck ID can post locations for it |
| Scale | One JSON file rewritten on each change, and the public OSRM/Nominatim/Photon servers: fine for a few trucks and residents, not a whole city |
| Aggressive phone brands | Some (Xiaomi, Oppo, Vivo, Samsung, …) stop background apps unless the app's battery setting is "Unrestricted" (SETUP.md section 9) |

---

## 16. Checking your changes

- **Server:** `python -m py_compile *.py` in `server/` catches syntax errors;
  run the server and use the admin panel, or drive a pretend truck with
  `python simulate.py <TRUCK-ID>` (section 14).
- **Admin panel:** `node --check admin/admin.js` checks the JavaScript.
- **Apps:** `gradlew assembleDebug` in each app folder (no warnings
  expected), then install on a phone or the emulator (SETUP.md sections 9
  and 11). On the emulator, move the "phone" along a road with
  **Extended controls → Location** (or `adb emu geo fix <lng> <lat>`) to
  test the driver app and the resident alerts.
