# EBook Reader

An offline-first Android reader for EPUB and PDF books, with a library manager, bookmarks and reading statistics. Built entirely with Jetpack Compose.

<p align="left">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Language" src="https://img.shields.io/badge/kotlin-1.9.23-7F52FF?logo=kotlin&logoColor=white">
  <img alt="UI" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
</p>

## Features

**Reading**
- EPUB and PDF in a single library
- Resumes exactly where you stopped — chapter *and* scroll offset within it
- Light, dark and sepia themes; adjustable font size
- Table of contents, internal link navigation and chapter scrubbing for EPUB
- Pinch-to-zoom and page scrubbing for PDF
- Bookmarks with optional notes

**Library**
- Import from any file picker, or open a book directly from another app
- Metadata, author and cover art read from the EPUB itself
- Grid and list layouts, search, sort by title/author/date/last read
- Filter by format, favourites or reading status

**Statistics**
- Per-session and per-book reading time, counted only while the app is in the foreground
- Monthly and yearly summaries with drill-down history
- Most-read leaderboards; history is retained even after a book is removed
- Export and import the whole history as a JSON file (Stats screen ⋮ menu), so it survives
  a reinstall or moves to a new device. Books are matched by title + author, since row ids
  mean nothing outside the device that made them; import either merges (skipping sessions
  the device already has) or replaces the local history outright.

## Requirements

| | |
|---|---|
| Android | 8.0 (API 26) or newer |
| JDK | 17 (Android Gradle Plugin 8.3 does not support JDK 22+) |
| Android SDK | Platform 34, Build-Tools 34 |
| Android Studio | Hedgehog (2023.1.1) or newer — optional, Gradle CLI is enough |

## Getting started

```bash
git clone https://github.com/<owner>/epubreader.git
cd epubreader
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In Android Studio, open the project root and run the `app` configuration. Gradle downloads the required SDK packages on first sync.

If `./gradlew` fails with a message naming your Java version, you are not on JDK 17:

```bash
java -version                                    # check
export JAVA_HOME=/path/to/jdk-17                 # macOS/Linux
$env:JAVA_HOME = "C:\path\to\jdk-17"             # Windows PowerShell
```

Android Studio's bundled JDK is often newer than 17; set **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** to a JDK 17 instead.

## Project layout

```
app/src/main/kotlin/com/ebookreader/
├── data/
│   ├── epub/          EPUB container, OPF and NCX parsing; chapter extraction
│   ├── local/         Room database, entities and DAOs
│   └── repository/    Repository implementations
├── domain/
│   ├── model/         Domain models, reading-session tracking
│   └── repository/    Repository interfaces
├── presentation/
│   ├── library/       Library, import, book options
│   ├── reader/epub/   WebView-based EPUB reader
│   ├── reader/pdf/    PdfRendererView-based PDF reader
│   ├── settings/      Settings
│   ├── stats/         Statistics and history
│   └── common/        Theme and shared helpers
└── di/                Hilt modules
```

Architecture is MVVM over a repository layer: Compose screens observe a `StateFlow` of immutable UI state from a `ViewModel`, which talks only to repository interfaces defined in `domain`. Room and DataStore sit behind those interfaces.

## Tech stack

| Concern | Library |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| Dependency injection | Hilt |
| Persistence | Room (books, bookmarks, sessions), DataStore (settings) |
| Images | Coil |
| PDF rendering | [Pdf-Viewer](https://github.com/afreakyelf/Pdf-Viewer) |
| EPUB rendering | WebView with a custom parser |
| Dependencies | Gradle version catalog (`gradle/libs.versions.toml`) |

## Database migrations

The Room schema is exported to `app/schemas/` and committed. Destructive fallback is **disabled** for shipped versions — dropping the database would delete every user's library, progress and statistics.

When you change an entity:

1. Increment `version` in `AppDatabase.kt`.
2. Add a `Migration(old, new)` to `DatabaseModule.kt`.
3. Build once and diff the newly generated `app/schemas/<version>.json` against your migration SQL — the `CREATE TABLE` and `CREATE INDEX` statements must match exactly, including column order and defaults.
4. Test the upgrade on a device that already holds the previous version.

## Release signing

Releases are signed with a keystore that is **not** in the repository. Losing it means no future build can update an installed copy of the app — users would have to uninstall first, wiping their library.

**Local setup.** Place `epubreader-release.jks` in the project root next to `settings.gradle.kts`, and create `keystore.properties` beside it:

```properties
storeFile=epubreader-release.jks
storePassword=<store password>
keyAlias=epubreader
keyPassword=<key password>
```

Both files are covered by `.gitignore`. Without them the project still builds; `assembleRelease` just produces an unsigned APK.

**Moving to another machine.** Copy `epubreader-release.jks` and `keystore.properties` through a channel you trust (password manager, encrypted archive) — never email or a public repository. Nothing else is machine-specific. Verify the copy matches the original before relying on it:

```bash
keytool -list -v -keystore epubreader-release.jks -storetype PKCS12
```

The SHA-256 certificate fingerprint must be identical on both machines. If it differs, the copy is a different key and cannot update the published app.

**Creating a new keystore** (only for a fresh app, never as a replacement for a lost one):

```bash
keytool -genkeypair -v -keystore epubreader-release.jks -storetype PKCS12 \
  -alias epubreader -keyalg RSA -keysize 4096 -validity 10000
```

## Automated releases

`.github/workflows/release.yml` runs on every push to `main`: it reads `versionName` from `app/build.gradle.kts`, and if no tag `v<versionName>` exists yet it builds a signed release APK and publishes it as **EBook Reader v<versionName>**.

To cut a release, bump both values in `app/build.gradle.kts` and push to `main`:

```kotlin
versionCode = 4        // must increase for every published build
versionName = "1.3"    // becomes tag v1.3 and title "EBook Reader v1.3"
```

Pushes that do not change `versionName` build and then skip publishing, so routine commits never fail. The About screen reads `versionName` from `BuildConfig`, so it never needs a manual edit.

**Required repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Store password |
| `KEY_ALIAS` | `epubreader` |
| `KEY_PASSWORD` | Key password |

Encode the keystore with:

```bash
base64 -w 0 epubreader-release.jks > keystore.b64      # Linux
base64 -i epubreader-release.jks -o keystore.b64       # macOS
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("epubreader-release.jks")) | Set-Content keystore.b64
```

Paste the contents of `keystore.b64` into `KEYSTORE_BASE64`, then delete the file. The workflow decodes it, builds, verifies the signature and removes the credentials before finishing.

## Contributing

1. Fork and branch from `main` (`git checkout -b feature/short-description`).
2. Match the surrounding style: 4-space indent, trailing commas in multi-line parameter lists, `StateFlow`-driven Compose screens with no logic in composables.
3. Keep the layer boundaries — composables call ViewModels, ViewModels call repository interfaces, only `data/` touches Room or DataStore.
4. Run `./gradlew assembleDebug` before opening a pull request. Pull requests are also built automatically by `.github/workflows/build.yml`.
5. Describe user-visible changes and note any schema change plus its migration.

Good places to start: R8/minification rules for release builds, horizontal paged EPUB layout, per-book font and theme overrides, EPUB text search, cover extraction for PDFs.

## License

MIT — see [LICENSE](LICENSE).
