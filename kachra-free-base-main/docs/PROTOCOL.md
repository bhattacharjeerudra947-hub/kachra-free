# Kachra Free — Communication Protocol

This documents how `driver-app` and `resident-app` talk to the central
server: endpoints, JSON schemas, on-device services, storage, and timing.
The server does not exist yet (see [SETUP.md](SETUP.md) / [AGENTS.md](AGENTS.md)) —
this file is the contract both apps already assume, so whoever builds the
server next can implement against it directly.

Everything below uses plain JSON over HTTP, no auth, no SDKs beyond what's
built into Android (`java.net.HttpURLConnection` + `org.json`). That's a
deliberate choice per AGENTS.md's "keep it simple" principle — swap in
something heavier (Retrofit, TLS, auth tokens) only when the prototype
actually needs it.

---

## 1. Architecture at a glance

```text
driver-app    --POST truck location every 10s-->  SERVER  <--GET status every 15s--   resident-app
                                                      |                                     |
                                                (not built yet)                    GET /api/places/search
                                                      |                            (proxies to a mapping
                                              holds all 3rd-party                   provider using a key
                                              API keys, admin-                      the server holds -
                                              panel configured                      see section 6)
```

Both apps are local-first: every action succeeds and is saved on the phone
whether or not the server can be reached. A failed server call just means
"try again on the next timer tick" — never a crash or blocked UI.

---

## 2. driver-app

**Purpose:** continuously report one truck's GPS location. No map, no
routes, minimal UI — see AGENTS.md section 1.

### Key files

| File | Role |
|---|---|
| `MainActivity.kt` | Truck ID entry, start/stop button, live coordinates + "last update Xs ago" display. |
| `LocationService.kt` | Foreground service: owns the GPS listener, the server upload, and the notification. |
| `AppPrefs.kt` | Shared preferences file/keys, shared between the Activity and the Service. |
| `ServerConfig.kt` | The one place `BASE_URL` and endpoint paths live. |

### Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS` (API 33+), `INTERNET`.
Manifest also sets `usesCleartextTraffic="true"` so plain `http://` works
against a server that doesn't have TLS yet — remove this once it does.

### Location behavior

- Registers for both `GPS_PROVIDER` and `NETWORK_PROVIDER` updates with
  `minTime = 10_000ms`, `minDistance = 0m` — a fix is delivered every 10
  seconds regardless of whether the truck actually moved (a stopped truck
  still reports).
- If the user turns Location off entirely, the service does **not** stop.
  It clears the on-screen coordinates back to "waiting for GPS" and keeps
  the registration alive — Android resumes delivering fixes automatically
  the moment Location is back on.
- The service is `START_STICKY`: if Android kills it, it restarts itself
  using the last-saved Truck ID from `AppPrefs` and resumes tracking with
  no user action needed.
- `LocationService.isRunning` (in-process, `@Volatile`) is the live truth
  for "is tracking actually active right now" — `MainActivity` polls it
  once a second instead of trusting a possibly-stale saved flag.

### Local storage (`SharedPreferences`, file `driver_prefs`)

| Key | Type | Meaning |
|---|---|---|
| `truck_id` | String | Last registered Truck ID. |
| `tracking` | Boolean | Whether tracking *should* be on (used to resume after a forced restart). |

### Server call: report location

```
POST {BASE_URL}/api/trucks/location
Content-Type: application/json
```

Request body:

```json
{
  "truckId": "TRUCK-1",
  "latitude": 12.971600,
  "longitude": 77.594600,
  "timestamp": 1732616400000
}
```

- `timestamp` is `Location.getTime()` — UTC epoch milliseconds of the GPS
  fix, not when the request was sent.
- Any 2xx response is treated as success. Any other response, or a network
  failure, is silently dropped — the next fix (10s later) tries again. The
  driver-app notification reflects the last attempt: `"...last update
  sent"` or `"...server unreachable, retrying"`.
- The server response body is currently ignored entirely. It's free to
  return `200 OK` with an empty body, or a JSON acknowledgement — nothing
  in the app depends on it yet.

---

## 3. resident-app

**Purpose:** one-time registration (phone number, house location, alert
preference) plus a live status/ETA display with a map — see AGENTS.md
sections 2–3 and 5.

### Key files

| File | Role |
|---|---|
| `MainActivity.kt` | Owns all state; switches between `RegisterScreen`, `StatusScreen`, and `TruckMapScreen`. |
| `ui/main/RegisterScreen.kt` | Phone number, house location summary + "Choose on map" button, alert-range radio buttons. |
| `ui/main/StatusScreen.kt` | Read-only view of the saved registration plus the latest server-reported truck/ETA, with a "View on map" button. |
| `LocationPickerActivity.kt` / `ui/main/LocationPickerScreen.kt` | Full-screen "drop a pin" house-location picker (see §3.2). |
| `ui/main/TruckMapScreen.kt` | AGENTS.md §5's map: house marker, truck marker, expected path, ETA. |
| `ResidentApi.kt` | Register + fetch-status HTTP calls. |
| `PlacesApi.kt` | Location *search* HTTP call — deliberately separate from ResidentApi, see §6. |
| `AppPrefs.kt` / `ServerConfig.kt` | Same pattern as driver-app. |

### Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `INTERNET`. No
`FOREGROUND_SERVICE*` permissions — the resident is never tracked in the
background, matching AGENTS.md 16.1. `usesCleartextTraffic="true"` for the
same http-during-development reason as driver-app.

### 3.1 Dependencies

`com.google.android.gms:play-services-maps` + `com.google.maps.android:maps-compose`
render the map (`GoogleMap`/`Marker`/`Polyline` composables). That's it —
no Places SDK client library, on purpose (§6).

### 3.2 House location picker (`LocationPickerActivity`)

A full-screen "drop a pin" picker — the same pattern Zomato/Swiggy/Uber use:
the map pans freely underneath a pin fixed at the exact screen center
(simpler and more reliable than a draggable marker). Started for a result
from `MainActivity`:

- In: `initial_latitude` / `initial_longitude` (optional `double` extras —
  omitted on first-time registration, pre-filled when editing).
- Out (on confirm): `result_latitude`, `result_longitude` (`double`),
  `result_address` (`String?` — reverse-geocoded via Android's built-in
  `Geocoder`, null if that lookup failed; the picker still returns
  coordinates either way).

Inside the picker: a search bar (see §3.3), a "🧭" FAB that centers the map
on a one-shot device location read (same permission-gated, self-unregistering
`LocationListener` pattern driver-app uses — not continuous tracking), and a
bottom "Confirm this location" button that reads whatever's currently under
the center pin.

### 3.3 Location search (`PlacesApi.kt`)

Typing in the picker's search bar calls the resident app's own server, not
a mapping provider directly:

```
GET {BASE_URL}/api/places/search?query={url-encoded text}
```

Expected response body:

```json
{
  "results": [
    { "name": "Koramangala, Bengaluru", "latitude": 12.9352, "longitude": 77.6146 },
    { "name": "Koramangala Club", "latitude": 12.9340, "longitude": 77.6120 }
  ]
}
```

- The server is expected to proxy this to whichever geocoding/places
  provider it's configured with (Google Places, Mapbox, OSM Nominatim,
  whatever the admin panel points it at) — the app doesn't know or care
  which.
- `results` may be empty (genuinely no matches) — the app distinguishes
  that from "couldn't reach the server at all" (`PlacesApi.search` returns
  `null` on any network failure) and shows a different message for each.
- Tapping a result moves the picker's map camera there; it does not
  immediately confirm the location — the resident still fine-tunes with
  the pin and taps "Confirm".

### Local storage (`SharedPreferences`, file `resident_prefs`)

| Key | Type | Meaning |
|---|---|---|
| `registered` | Boolean | Whether registration has been completed at least once. |
| `phone_number` | String | Resident's phone number. |
| `latitude` / `longitude` | String | House location (kept as text so it round-trips exactly through the form). |
| `address` | String? | Reverse-geocoded label for the house location, if available; falls back to raw coordinates in the UI when absent. |
| `alert_minutes` | Int | One of 5 / 10 / 15 / 30, per AGENTS.md section 3. |

Registration is local-first, same as driver-app: saving to
`SharedPreferences` always succeeds; the server POST is a best-effort sync
on top of that.

### Server call: register or update a resident

```
POST {BASE_URL}/api/residents/register
Content-Type: application/json
```

Request body:

```json
{
  "phoneNumber": "+911234567890",
  "latitude": 12.971600,
  "longitude": 77.594600,
  "alertMinutes": 10,
  "address": "12 MG Road, Bengaluru"
}
```

- `address` is optional and purely a display convenience (e.g. for an
  admin panel list) — omitted from the request entirely when the app has
  no reverse-geocoded label.
- Treated as an **upsert** — calling it again with the same `phoneNumber`
  (e.g. editing house location later) should update the existing resident
  record, not create a duplicate. `phoneNumber` is therefore the natural
  key the server should key residents on.
- Any 2xx is success. Failure just means "still saved locally, will retry
  implicitly next time the resident edits and re-saves" — there's no
  automatic background retry loop for registration itself.

### Server call: fetch truck status / ETA

```
GET {BASE_URL}/api/residents/status?phone={url-encoded phoneNumber}
```

Expected response body:

```json
{
  "truckId": "TRUCK-1",
  "truckLatitude": 12.970000,
  "truckLongitude": 77.590000,
  "etaMinutes": 8,
  "path": [
    { "latitude": 12.9700, "longitude": 77.5900 },
    { "latitude": 12.9710, "longitude": 77.5930 },
    { "latitude": 12.9716, "longitude": 77.5946 }
  ]
}
```

- All fields are optional/nullable from the client's point of view.
- `path` is the truck's **expected route** to the house (AGENTS.md section
  6 — the historical-collection-order-aware path, not a straight line).
  `TruckMapScreen` only draws a polyline when `path` has 2+ points; with
  it omitted or empty, the map just shows the house and truck markers with
  no connecting line, since AGENTS.md section 5 is explicit that a naive
  straight line would be misleading.
- The app renders "Waiting for the server..." if the call fails entirely,
  and "ETA: not available yet" if `etaMinutes` is missing even though the
  call succeeded (e.g. server has the resident but no route/ETA computed
  yet).
- Polled every 15 seconds while `StatusScreen` or `TruckMapScreen` is
  visible (`MainActivity`'s `STATUS_POLL_INTERVAL_MS`); stopped while the
  registration form is open or the app is backgrounded.
- The server is expected to do the work described in AGENTS.md sections
  6–11 (route-aware ETA, correct-truck selection) to produce this — the
  resident app itself has no routing logic, it only displays what the
  server sends.

---

## 4. Server contract summary

| Endpoint | Method | Called by | Frequency | Purpose |
|---|---|---|---|---|
| `/api/trucks/location` | POST | driver-app | every 10s while tracking | Report current truck GPS fix. |
| `/api/residents/register` | POST | resident-app | on register / on edit-save | Create or update a resident record. |
| `/api/residents/status` | GET | resident-app | every 15s while status/map screen is open | Fetch the relevant truck + ETA (+ expected path) for a resident. |
| `/api/places/search` | GET | resident-app | on each search-bar submit in the location picker | Proxy a location-name search to whatever geocoding provider the server is configured with. |

None of these exist server-side yet. Both apps already point at
`http://10.0.2.2:8080` (the Android emulator's alias for the host
machine's localhost) via `ServerConfig.BASE_URL` in each project — update
that one constant per app once a real server address exists.

---

## 5. Known gaps (not built yet)

These are explicitly deferred, per AGENTS.md's phased priority (section 17)
and the "keep it simple" principle — not oversights:

- **The server itself** — `server/` is empty. Everything above is the
  contract it needs to implement, including `/api/places/search`, which
  is what makes the resident app's map picker's search bar do anything.
- **Push notifications / SMS** (AGENTS.md section 12) — no Firebase Cloud
  Messaging integration in resident-app, and SMS is entirely a
  server-side responsibility (e.g. via a provider like Twilio) that
  doesn't touch the resident app at all.
- **Route prediction / ETA engine / traffic integration** (sections 6–10)
  — entirely server-side; `/api/residents/status`'s `path` and
  `etaMinutes` fields are already there for it to fill in.
- **Admin panel** (section 13–14) — not started, but §6 below already
  assumes it will be where third-party API keys get configured.

---

## 6. API key handling (security)

**Policy:** third-party API keys belong on the server, configured through
the (not-yet-built) admin panel — never shipped inside an APK, where
they're one `unzip` away from being read by anyone.

**One unavoidable, documented exception:** the Google Maps SDK for Android
renders map tiles on-device and calls Google's servers directly from the
phone, so its key (`com.google.android.geo.API_KEY` in resident-app's
`AndroidManifest.xml`, sourced from `local.properties` → `MAPS_API_KEY` via
Gradle `manifestPlaceholders`, never hardcoded) genuinely has to live in
the installed app. This is true for every app that renders native Google
Maps — Uber, Swiggy, Ola all ship this same kind of key in their APKs.
Google's own mitigation for this isn't concealment, it's **restriction**:
in Google Cloud Console, restrict the key to
  - *Application restriction:* Android apps, listing this app's package
    name (`com.kachrafree.resident`) + your signing certificate's SHA-1
    fingerprint, and
  - *API restriction:* Maps SDK for Android only.

Once restricted, the key is useless to anyone outside this exact signed
app, and a usage quota/budget alert caps the worst case even then. Maps
SDK for Android rendering itself has no per-load charge; you still need a
billing account on the Cloud project to get a key issued at all.

**Everything else stays server-side.** Location search (§3.3) is a plain
request/response call, so unlike map rendering there's no technical reason
its key can't live entirely on the server — `PlacesApi.kt` calls *our*
server, never a mapping provider directly, and the server's own
Places/geocoding key (configured via the future admin panel) is what
actually talks to Google/Mapbox/whoever. The same should apply to any
future third-party integration (SMS provider, traffic data, etc.): the
app calls our server, our server holds the key.
