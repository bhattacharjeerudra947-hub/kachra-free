# Development log

How Kachra Free got to its current shape: what was tried, changed, removed
or fixed, and why. For what the project does *now*, read
[EXPLAINER.md](EXPLAINER.md).

---

## Maps, routing and search

- **Google Maps → OpenStreetMap.** The resident app and admin panel first
  used Google Maps, with the idea of keeping the API key on the server so it
  never ships inside an APK. Google needs a debit/credit card on file to
  issue a key, so everything moved to free OpenStreetMap services: osmdroid
  (app maps), Leaflet (admin map), OSRM (roads and drive times) and
  Nominatim (search). The project has no API keys at all.
- **No live traffic.** Every live-traffic provider is paid, so drive time is
  OSRM's normal road time, refreshed from the truck's real position.
- **Search suggestions via Photon.** Nominatim's usage policy forbids
  search-as-you-type, so suggestions while typing come from Photon (another
  free OpenStreetMap search built for it). Nominatim is still used for the
  Go button.
- **Country filter removed.** A `searchCountryCodes` setting once narrowed
  search to one country; it was removed to keep settings minimal.
- **No straight lines.** An early version drew a straight line when no road
  was known. It was removed: a leg is drawn only as a real road (as driven,
  or the OSRM route), otherwise not at all.
- **History only for stop times.** Past *drive* times between stops were
  considered for the ETA, then dropped: history only gives each stop's
  typical collecting time and "usually around" time; drive time comes from
  OSRM (distance ÷ average car speed when OSRM is down, shown in the admin
  Alerts panel).

## Residents and alerts

- **SMS removed.** The spec asked for SMS for residents without smartphones.
  Every SMS provider (e.g. Twilio) costs money, so SMS and the notification
  preference were removed; alerts are app notifications only.
- **Phone number → random ID → username.** Residents were first identified
  by phone number. With SMS gone it wasn't needed, so the app made up a
  random ID instead. That allowed duplicates (a reinstall made a new
  resident), so residents now register with a **username**: the same
  username is the same resident on any phone.
- **No Firebase push.** Alerts reach a closed app through the resident
  app's own foreground service polling the server (every 15 s), so no push
  service or account is needed.
- **Admin can't add residents.** The admin panel first registered residents
  on their behalf (for people without smartphones). Without SMS such a
  resident could never get an alert, so adding was removed; the admin panel
  edits and deletes residents only.
- **Alert time: buttons → number.** The alert time was a choice of 5 / 10 /
  15 / 30 minutes; it's now any whole number from 1 to 120.

## Trucks and stops

- **Truck ID instead of automatic truck selection.** Picking "the relevant
  truck" automatically needs route data we don't have, so residents enter
  their Truck ID and are served at that truck's nearest stop.
- **Trucks must exist.** The server first created a truck the first time a
  driver app sent an unknown Truck ID. Now trucks are added in the admin
  panel, and the driver app checks its Truck ID with the server before it
  starts tracking.

## Collection and live updates

- **Automatic stop detection → "Garbage collected" button.** The server
  used to decide a stop was visited when the truck came within 50 m of it,
  and measured "collecting time" from how long it stayed. That was
  guesswork, so the driver now presses **Garbage collected** (offered
  within 30 m of a stop). Real rounds record only when each stop was
  collected; stoppage times (arrival → departure) exist only in demo
  history until stationary-truck tracking is added. A stop not collected
  while a later one was is marked **skipped**.
- **Online status fixed.** A truck stayed "online" for 2 minutes after the
  driver stopped sharing. Now Stop marks it offline at once, and any other
  loss (no internet, no GPS, phone off) shows after 10 s without a location.
- **Faster updates.** Everything was sped up from 10–15 s to 1 s, then
  settled at **2 s** (driver location, resident status, admin panel):
  each update is a request, and ngrok's free plan has a monthly request
  limit. Duplicate requests were removed (no server pings while an app is
  already talking to the server; a new request isn't started while the
  last one is still waiting).
- **Notifications** now open the app when tapped, and the resident's
  ongoing notification shows a short, live one-line status.

## Security: open app endpoints (to do later)

What's protected today: the **admin panel** (random 12-character password,
HTTP Basic auth over ngrok's HTTPS).

What isn't: every request the **apps** make has no login. The ngrok address
is in both apps' `ServerConfig.kt`, inside both APKs, and in the GitHub
repo's history, so anyone with it can:

- **Spoof a truck** (knowing or guessing its Truck ID, e.g. `001`): send
  fake locations, press "Garbage collected" at any stop (residents see
  collected/skipped), mark the truck offline, add stops.
- **Impersonate a resident:** registration needs only a username, so
  registering with someone else's takes over their record; looking up a
  username's status reveals their collection point (roughly where they live).
- **Burn quotas:** flood the server to use up the ngrok free plan's monthly
  request limit, or overuse the search endpoints so the public
  Nominatim/Photon servers block the laptop's IP.

Fixes, in order of value:

1. **Per-truck driver PIN.** The admin sets a secret PIN per truck; the
   driver app sends Truck ID + PIN; the server rejects driver requests
   without the right PIN. Stops truck spoofing (the most damaging abuse).
2. **Resident password.** Register with username + password; registering
   again and checking status need it. Stops takeovers and location snooping.
3. **Rate limiting.** Refuse more than N requests per minute per IP, to
   protect the ngrok quota and the free search servers.
4. **Run the tunnel only while testing or demoing** (free, immediate).

Not worth doing: one shared "app key" baked into both apps. The APKs are
public, so it can be pulled out in minutes; it only stops casual attempts.

## Code style passes

Two passes rewrote the code to read like a hand-written prototype (plain
loops and ifs, small named functions, no clever one-liners), without
changing behaviour. A later cleanup removed compatibility code for older
versions (default values for old records, an `ADMIN_PASSWORD` environment
variable, merging old database files into new defaults), keeping only the
fallbacks for failed API calls. The conventions are written down in
[CLAUDE.md](../CLAUDE.md).

## Bugs found and fixed

- **ETA skipped the stop being collected.** Found on the emulator: when the
  truck parked at a stop before the resident's, that stop counted as done at
  once, so the ETA jumped (6 → 2 min) and a 5-minute alert arrived at "2
  minutes". The ETA now includes the collecting time still left at the stop
  the truck is standing at; the alert then fired at exactly "5 minutes".
- **Stale APK.** An early test showed "server unreachable" and missing
  features because an APK built before the change was installed. Rebuild
  after every source change (SETUP.md section 9).
- **Slow simulator on Windows.** `localhost` tries IPv6 first and stalls
  about 2 s per request; the simulator posts to `127.0.0.1`.
- **Driven roads not captured.** Track times were rounded to 0.1 s, which
  broke cutting out the road between two stop visits; they're now stored at
  full precision.
- **Misleading "server unreachable" after leaving a stop.** Between leaving
  a stop and the next road refresh there was no truck-to-stop route; the
  ETA now uses the cached stop-to-stop road from where the truck is.
- **Admin form fields named `id` / `name`** clashed with built-in form
  properties; renamed.
- **Picker screen overlap.** The address panel covered the my-location
  button; the panel now sits below the map.
- **Old resident records couldn't be deleted** once usernames were
  validated (an old 36-character ID broke the rules); deleting no longer
  validates.

## Testing history

- The server was tested with a scripted end-to-end scenario (with a fake
  and the real OSRM): registration (unknown truck, missing fields, bad
  usernames, the same username twice), ETA counting down, driven-road
  capture, `at_stop` / `collected`, one alert per round, demo history,
  driver add-stop, GPS track, OSRM failure → Alerts panel + rough estimate,
  unknown Truck IDs refused, admin auth.
- Both apps were run end to end on the Android emulator against the real
  server, ngrok, OSRM, Nominatim and Photon, moving the emulator's GPS along
  a real road to test ETAs and the timed alert.
- Emulator quirks met on the way: the keyboard's one-time "Try out your
  stylus" tutorial blocked typing (turned off with
  `adb shell settings put secure stylus_handwriting_enabled 0`), and
  `simulate.py --fast` passes stops too quickly to see "at your stop".
