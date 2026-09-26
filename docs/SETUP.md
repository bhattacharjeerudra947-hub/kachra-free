## Dependencies

| Dependency                 | Purpose                          |
| -------------------------- | -------------------------------- |
| JDK 17                     | Runs Gradle/Android build tools  |
| Android SDK                | Android development/build tools  |
| Android Command-Line Tools | Manage Android SDK packages      |
| Android Platform Tools     | `adb` device communication     |
| Android SDK Platform 36    | Android API used for compilation |
| Android Build Tools 36.0.0 | Build/package APK                |
| Python 3.10+               | Runs the server + admin panel (`server/`), standard library only |
| ngrok (free account)       | Exposes the laptop server to the internet |

Gradle isn't installed separately: each app's `gradlew` / `gradlew.bat`
downloads the exact Gradle version it needs on first use.

Maps (both the resident app and the admin panel), road routes, drive times
and location search all use free OpenStreetMap services (osmdroid, OSRM,
Nominatim). None of them need an account, key or card.

---

# Windows Setup

## 1. Install JDK 17

Download **Eclipse Temurin JDK 17**: [https://adoptium.net/temurin/releases/?version=17](https://adoptium.net/temurin/releases/?version=17)

Install with:

```text
Set JAVA_HOME = enabled
Add to PATH = enabled
```

Verify:

```cmd
java -version
```

Expected:

```text
openjdk version "17.x.x"
```

Verify:

```cmd
echo %JAVA_HOME%
```

Expected:

```text
C:\Program Files\Eclipse Adoptium\jdk-17...
```

---

## 2. Install Android Command-Line Tools

Download: [https://developer.android.com/studio#command-line-tools-only](https://developer.android.com/studio#command-line-tools-only)

Download:

```text
Command line tools only → Windows
```

Create:

```text
C:\Android\
```

Extract so the final structure is:

```text
C:\Android\cmdline-tools\latest\
```

Required:

```text
C:\Android\cmdline-tools\latest\bin\
C:\Android\cmdline-tools\latest\lib\
```

`sdkmanager.bat` must be located at:

```text
C:\Android\cmdline-tools\latest\bin\sdkmanager.bat
```

### Important

Do **not** use:

```text
C:\Android\cmdline-tools\bin
```

Use:

```text
C:\Android\cmdline-tools\latest\bin
```

---

## 3. Add Android tools to PATH

Add:

```text
C:\Android\cmdline-tools\latest\bin
C:\Android\platform-tools
```

Open a new terminal.

Verify:

```cmd
sdkmanager --version
```

Expected:

```text
22.0
```

A deprecation warning about `sdkmanager` is currently expected.

---

## 4. Install Android SDK packages

Run:

```cmd
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Accept licenses:

```cmd
sdkmanager --licenses
```

Enter:

```text
y
```

for each license.

---

## 5. Verify ADB

Run:

```cmd
where adb
```

Expected:

```text
C:\Android\platform-tools\adb.exe
```

Run:

```cmd
adb --version
```

Expected:

```text
Android Debug Bridge version 1.0.41
```

---

## 6. Remove duplicate ADB installations

Check:

```cmd
where adb
```

If multiple paths are returned, keep:

```text
C:\Android\platform-tools\adb.exe
```

Remove older entries such as:

```text
C:\ADB\platform-tools
```

from `PATH`.

Open a new terminal and verify again:

```cmd
where adb
```

Expected:

```text
C:\Android\platform-tools\adb.exe
```

---

## 7. Install Python 3

Runs the server and the admin panel. The server only uses Python's standard
library, so there is no `pip install` step.

Download the **Windows installer**: [https://www.python.org/downloads/](https://www.python.org/downloads/)

In the installer, tick **Add python.exe to PATH**.

Open a new terminal.

Verify:

```cmd
python --version
```

Expected:

```text
Python 3.10 or newer
```

Run the server:

```cmd
cd server
python server.py
```

Expected:

```text
Kachra Free server on http://localhost:8080
Admin panel: http://localhost:8080/admin  (user: admin, password: <generated>)
```

The admin password is generated on first run and saved in
`server\data\admin_password.txt`.

The first time, Windows Firewall may ask whether Python can accept
connections. Allow **Private networks**, so phones on the same Wi-Fi can
reach the server; ngrok doesn't need this.

---

## 8. Install ngrok and reserve a free static domain

Lets phones on any network (e.g. a driver on mobile data) reach the server
running on this laptop. See [EXPLAINER.md](EXPLAINER.md) section 13 for why.

1. Create a free account: [https://dashboard.ngrok.com/signup](https://dashboard.ngrok.com/signup)
2. Download the Windows agent: [https://ngrok.com/download](https://ngrok.com/download)
3. Extract `ngrok.exe` to:

```text
C:\DevTools\ngrok\ngrok.exe
```

4. Add to `PATH`:

```text
C:\DevTools\ngrok
```

5. Open a new terminal and connect the agent to your account. Your token is
   on the dashboard under **Your Authtoken**:

```cmd
ngrok config add-authtoken <YOUR_AUTHTOKEN>
```

6. On the dashboard, go to **Domains** and claim your one free static
   domain, e.g.:

```text
your-name.ngrok-free.app
```

Verify:

```cmd
ngrok version
```

Expected:

```text
ngrok version 3.x.x
```

Set `BASE_URL` in **both** apps' `ServerConfig.kt` to that domain:

```text
https://your-name.ngrok-free.app
```

- `driver-app/app/src/main/java/com/example/kachrafreedriver/ServerConfig.kt`
- `resident-app/app/src/main/java/com/example/kachrafreeresident/ServerConfig.kt`

and rebuild both apps (section 9) — this is a source change, so the already
installed APKs won't pick it up until reinstalled. This is one-time setup;
starting the tunnel itself is a day-to-day step, covered in section 10.

---

## 9. Building and installing the apps

Whenever `driver-app` or `resident-app`'s source changes — including the
`BASE_URL` edit above — rebuild before testing. Both use the same commands,
run from that app's own folder:

```cmd
cd driver-app
gradlew assembleDebug
```

Builds the APK to `app\build\outputs\apk\debug\app-debug.apk`, without
installing it anywhere.

With a phone connected over USB (Developer options → USB debugging on, then
accept the "Allow USB debugging?" prompt) and showing up in `adb devices`:

```cmd
gradlew installDebug
```

Builds and installs it on the connected phone in one step — the normal
command to use after a code change. It reinstalls over the existing app
without erasing its saved registration/Truck ID.

Same for the other app:

```cmd
cd ..\resident-app
gradlew installDebug
```

If more than one device is connected (e.g. an emulator and a phone),
`gradlew installDebug` installs to all of them. To target just one, get its
ID from `adb devices` and either disconnect the others, or install directly
with `adb`:

```cmd
adb -s <device-id> install -r app\build\outputs\apk\debug\app-debug.apk
```

(`-r` reinstalls over an existing copy, keeping its data.)

---

## 10. Running a demo

Everything above is one-time setup. This is what to actually run, in order,
every time:

1. Start the server:
   ```cmd
   cd server
   python server.py
   ```
   Expected:
   ```text
   Kachra Free server on http://localhost:8080
   Admin panel: http://localhost:8080/admin  (user: admin, password: <generated>)
   ```
2. In a second terminal, start the tunnel:
   ```cmd
   ngrok http --domain=your-name.ngrok-free.app 8080
   ```
3. Check it: open `https://your-name.ngrok-free.app/admin` in a browser and
   log in with the password printed in step 1.
4. If `driver-app` or `resident-app`'s source changed since the last demo
   (including a different ngrok domain), rebuild and reinstall them first —
   section 9.

Both terminals need to stay open for the whole demo. If the laptop or either
process restarts, phones just show "server unreachable" until both are
running again — nothing on the phones needs redoing.

No real truck handy? `python simulate.py TRUCK-1 --fast` (in `server/`, with
the server running) drives a pretend one — see [EXPLAINER.md](EXPLAINER.md)
section 14.

---

# Final Verification

Run:

```cmd
java -version
```

```cmd
echo %JAVA_HOME%
```

```cmd
sdkmanager --version
```

```cmd
adb --version
```

```cmd
where adb
```

```cmd
python --version
```

```cmd
ngrok version
```

Expected toolchain:

```text
Java       → 17
Android    → C:\Android
ADB        → C:\Android\platform-tools\adb.exe
API        → Android 36
BuildTools → 36.0.0
Python     → 3.10+
ngrok      → 3.x (authtoken configured, static domain reserved)
```

No Android Studio is required. Build an app with `gradlew assembleDebug` from its folder.
