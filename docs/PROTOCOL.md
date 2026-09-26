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
driver-app  --POST truck location every 10s-->  SERVER  <--GET status every 15s--  resident-app
                                                    |
                                              (not built yet)
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
preference) plus an optional live status/ETA display — see AGENTS.md
sections 2–3.

### Key files

| File | Role |
|---|---|
| `MainActivity.kt` | Owns all state; shows `RegisterScreen` or `StatusScreen` depending on whether the resident is registered. |
| `ui/main/RegisterScreen.kt` | Phone number, house location (manual lat/lng + "use my current location"), alert-range radio buttons. |
| `ui/main/StatusScreen.kt` | Read-only view of the saved registration plus the latest server-reported truck/ETA. |
| `ResidentApi.kt` | The two HTTP calls (register, fetch status). |
| `AppPrefs.kt` / `ServerConfig.kt` | Same pattern as driver-app. |

### Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `INTERNET`. No
`FOREGROUND_SERVICE*` permissions — the resident is never tracked in the
background, matching AGENTS.md 16.1. `usesCleartextTraffic="true"` for the
same http-during-development reason as driver-app.

### House location capture

There's no map picker yet (that needs a Maps SDK + API key, deliberately
out of scope for now — see §5). "Use my current location" does a
**single** location read (`getLastKnownLocation`, or one `LocationListener`
callback that immediately unregisters itself) to prefill the latitude/
longitude fields, which remain plain editable text either way. This is a
one-shot convenience, not tracking.

### Local storage (`SharedPreferences`, file `resident_prefs`)

| Key | Type | Meaning |
|---|---|---|
| `registered` | Boolean | Whether registration has been completed at least once. |
| `phone_number` | String | Resident's phone number. |
| `latitude` / `longitude` | String | House location (kept as text so it round-trips exactly through the form). |
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
  "alertMinutes": 10
}
```

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
  "etaMinutes": 8
}
```

- All fields are optional/nullable from the client's point of view — the
  app renders "Waiting for the server..." if the call fails entirely, and
  "ETA: not available yet" if `etaMinutes` is missing even though the call
  succeeded (e.g. server has the resident but no route/ETA computed yet).
- Polled every 15 seconds while `StatusScreen` is visible (`MainActivity`'s
  `STATUS_POLL_INTERVAL_MS`); stopped while the registration form is open
  or the app is backgrounded.
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
| `/api/residents/status` | GET | resident-app | every 15s while status screen is open | Fetch the relevant truck + ETA for a resident. |

None of these exist server-side yet. Both apps already point at
`http://10.0.2.2:8080` (the Android emulator's alias for the host
machine's localhost) via `ServerConfig.BASE_URL` in each project — update
that one constant per app once a real server address exists.

---

## 5. Known gaps (not built yet)

These are explicitly deferred, per AGENTS.md's phased priority (section 17)
and the "keep it simple" principle — not oversights:

- **The server itself** — `server/` is empty. Everything above is the
  contract it needs to implement.
- **Push notifications / SMS** (AGENTS.md section 12) — no Firebase Cloud
  Messaging integration in resident-app, and SMS is entirely a
  server-side responsibility (e.g. via a provider like Twilio) that
  doesn't touch the resident app at all.
- **Map view with truck position + expected path** (AGENTS.md section 5) —
  would need a Maps SDK and API key. Deferred until the server can supply
  real route/ETA data to show; a live map with no backend would just be
  dead weight and adds a dependency for no payoff.
- **Route prediction / ETA engine / traffic integration** (sections 6–10)
  — entirely server-side; both apps are already wired to send/receive the
  data this would need.
- **Admin panel** (section 13–14) — not started.
