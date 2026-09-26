## Dependencies

| Dependency                 | Purpose                          |
| -------------------------- | -------------------------------- |
| JDK 17                     | Java runtime/compiler            |
| Android SDK                | Android development/build tools  |
| Android Command-Line Tools | Manage Android SDK packages      |
| Android Platform Tools     | `adb` device communication     |
| Android SDK Platform 36    | Android API used for compilation |
| Android Build Tools 36.1.0 | Build/package APK                |
| Gradle 9.6.0               | Build system                     |

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

## 7. Install Gradle 9.6.0

Download: [https://gradle.org/releases/](https://gradle.org/releases/)

Download:

```text
gradle-9.6-bin.zip
```

Extract to:

```text
C:\DevTools\gradle-9.6\
```

Required:

```text
C:\DevTools\gradle-9.6\bin\gradle.bat
```

Add directly to `PATH`:

```text
C:\DevTools\gradle-9.6\bin
```

Open a new terminal.

Verify:

```cmd
gradle --version
```

Expected:

```text
Gradle 9.6.0
JVM: 17.x.x
OS: Windows
```

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
gradle --version
```

Expected toolchain:

```text
Java       → 17
Android    → C:\Android
ADB        → C:\Android\platform-tools\adb.exe
API        → Android 36
BuildTools → 36.0.0
Gradle     → 9.6.0
```

No Android Studio is required.
