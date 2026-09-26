# Kachra Free

**Know when your garbage truck is coming.**

A driver's phone reports the truck's location. Each truck has an ordered list
of collection stops, where it parks and nearby residents bring their garbage.
A small server works out when the truck will reach each resident's stop:
drive time along real roads, plus the typical collecting time at each stop
before theirs. Residents get an app notification when it's within the time
they chose. Everything runs on free services.

| Folder | What |
|---|---|
| `driver-app/` | Android app for the truck: enter Truck ID, start, add collection stops as you go. |
| `resident-app/` | Android app for residents: enter your Truck ID, pick your house on a map, see the truck, its road and ETA, get alerts. |
| `server/` | Python server (standard library only) + admin panel at `/admin`. |
| `docs/` | [AGENTS.md](docs/AGENTS.md) (what we're building), [EXPLAINER.md](docs/EXPLAINER.md) (full technical explainer: every feature, formula, endpoint and dependency), [SETUP.md](docs/SETUP.md) (installing everything), [TODO_FOR_YOU.md](docs/TODO_FOR_YOU.md) (what still needs you). |

## Quick start

```text
cd server
python server.py                              # prints the admin password
ngrok http --domain=YOUR-NAME.ngrok-free.app 8080
```

1. Open `http://localhost:8080/admin`. Add a truck, click its stops onto the
   map in collection order, save. Optionally, **Generate demo history**.
2. Set `BASE_URL` in both apps' `ServerConfig.kt` to your ngrok URL, then
   build each app with `gradlew assembleDebug`.
3. Driver phone: enter the Truck ID and start sharing. Resident phone:
   register with the same Truck ID.
4. No truck handy? `python simulate.py TRUCK-1 --fast` drives a pretend one.

Full install steps: [docs/SETUP.md](docs/SETUP.md).
