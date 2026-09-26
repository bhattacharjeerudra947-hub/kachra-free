# Needs you

Everything that can be built and tested without real hardware is done. The
server, both apps and the ngrok tunnel are already wired together — see
[SETUP.md](SETUP.md) section 10 for how to actually launch a demo. The one
thing left is this:

---

## 1. Test on real phones

Both apps build without warnings, but I can't run Android apps from here.

1. On each phone: **Settings → About phone** → tap **Build number** 7 times →
   back to **Settings → System → Developer options** → turn on **USB debugging**.
2. Plug the phone into the laptop and accept the "Allow USB debugging?"
   prompt. Then check it shows up:
   ```cmd
   adb devices
   ```
3. Install the apps:
   ```cmd
   cd driver-app
   gradlew installDebug
   cd ..\resident-app
   gradlew installDebug
   ```
4. On **both** phones, set the app's battery setting so Android doesn't kill
   its background service. On Xiaomi, Samsung, Oppo, Vivo and others:
   **Settings → Apps → (Kachra Free Driver / Resident) → Battery →
   Unrestricted** (named "No restrictions" on some brands). The driver app
   needs this for GPS tracking, the resident app for background alerts.
5. Have the server and tunnel running (SETUP.md section 10) before testing.
   Things to check:
   - **Driver:** enter a Truck ID, start. Within about 10 s the screen should
     say "Server: connected". Lock the phone for 10+ minutes; tracking
     should keep going (the admin panel shows it updating).
   - **Driver:** press **Add stop here**, then **Undo** within 20 s: nothing
     should be added. Press it again and wait 20 s: the stop should appear on
     the phone and in the admin panel.
   - **Driver:** switch Location off and on while tracking. The screen should
     say "waiting for GPS", then resume by itself.
   - **Resident:** register with that Truck ID. Try a wrong ID first; it
     should be refused. Pick the house by search, by the 🧭 button, and by
     dragging the map.
   - **Resident:** as the truck approaches, check the ETA, the map (house,
     green collection point, truck, road; OpenStreetMap needs internet the
     first time an area is shown), and that the alert notification appears
     **once**.
   - **Resident, background alerts:** after registering, close the app and
     swipe it away from Recents. A "Kachra Free Resident - watching for truck
     alerts" notification should stay in the status bar, and the truck alert
     should still arrive with the app fully closed.
