# EBook Reader

An offline-first Android reader for EPUB, PDF, comics, Kindle, FB2 and text books, with a library manager, highlights, read-aloud and reading statistics. Built entirely with Jetpack Compose.

<p align="left">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Language" src="https://img.shields.io/badge/kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white">
  <img alt="UI" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
</p>

## Features

**Reading**
- EPUB and PDF in a single library
- Resumes exactly where you stopped — chapter *and* scroll offset within it
- Reading themes chosen apart from the app theme: Paper, Sepia, Slate, Night and Black pages,
  or Auto to follow the app; the reader's bars and sheets take the page's colours
- An **Aa** sheet inside the EPUB reader for size, typeface, line spacing, margins,
  justification, hyphenation, page colour, brightness and warmth, each applied to the page
  as it changes, without reloading it or losing your place
- Six reading faces, all bundled: Literata (default), Bitter, Atkinson Hyperlegible,
  OpenDyslexic, Inter Tight, or the book's own fonts. Text size follows the system font scale
- Dark pages that stay dark: a book's own text colours, white boxes and inline styles are
  overridden, and figures are dimmed so they do not glare
- Tap the left or right third of the page to turn it, the middle to show the controls; in
  horizontal mode, swipe to change chapter. Turning past either end of a chapter opens the
  next one, or the previous one at its end
- The chapters either side of the open one are kept loaded, so turning into them is instant
- Progress is weighted by how much text each chapter holds, so title pages and short
  chapters no longer skew the percentage
- Table of contents, internal link navigation and chapter scrubbing for EPUB
- Pinch-to-zoom and page scrubbing for PDF
- Bookmarks with optional notes
- Select text to copy, share, highlight in four colours, add a note, make a quote card (a
  shareable image of the passage) or find it elsewhere in the book
- Find in book, accent- and case-insensitive, landing on the exact occurrence
- **Read aloud** with the device's voices: the sentence being spoken is marked and kept in
  view, it carries on into the next chapter and with the screen off, and a media
  notification (lock screen, headset buttons) controls it. Speed from 0.75× to 2×
- Comics (CBZ, CBR) a page at a time: swipe or tap the outer thirds, pinch or double-tap to
  zoom into a panel, right-to-left for manga

**Library**
- Import from any file picker, or open a book directly from another app
- Formats: EPUB and PDF as they are; CBZ and CBR comics; MOBI and AZW3 (Kindle books without
  DRM), FB2, plain text and Markdown, converted to EPUB on import with their chapters,
  metadata and covers. DRM-protected Kindle books are refused with a reason
- **Online catalogues** (OPDS): Project Gutenberg built in, any other catalogue by its
  address. Browse, search, and download straight into the library. This is the only part
  of the app that goes online
- A continue-reading card with the time left in the chapter and the book, at your own pace
- Covers from the book, page one of a PDF, the first page of a comic, or a generated cloth
  cover with the title set on it
- Series and collections, multi-select (favourite, finish, collect, delete), and full-text
  search inside every book's text
- Grid and list layouts, search, sort by title/author/date/last read
- Filter by format, favourites or reading status
- A home-screen widget with the book you are reading, where you left it, and your streak

**Design**
- App theme: Auto, Light, Dark or Black (true black for OLED). No wallpaper-derived colour:
  the palette is fixed and contrast-tested (`BinderyContrastTest`)
- Each book's progress is drawn as its fore-edge, the ruled block of page edges on the
  right of its cover, stained as it is read
- Literata for titles, Inter Tight for the interface, IBM Plex Mono for figures, all bundled

**Statistics**
- Per-session and per-book reading time, counted only while the app is in the foreground
- A year of reading days drawn as page edges, streaks, the last seven days and twelve months
- Daily-minutes and books-a-year goals, with an optional evening reminder
- Per-book stats: sittings, pace, time left and a projected finish date
- A year-in-reading card to share
- Monthly and yearly summaries with drill-down history
- Most-read leaderboards; history is retained even after a book is removed
- Export and import the whole history as a JSON file (Stats screen ⋮ menu), so it survives
  a reinstall or moves to a new device. Books are matched by title + author, since row ids
  mean nothing outside the device that made them; import either merges (skipping sessions
  the device already has) or replaces the local history outright.

**Sync**
- Keep reading history, places and highlights in step between devices through a folder they
  already share (Syncthing, or a cloud drive's folder), chosen in Settings. No account and
  no server: the app reads and writes one JSON file there when you open and leave it

## Requirements

| | |
|---|---|
| Android | 8.0 (API 26) or newer |
| JDK | 17 to 26 (Gradle 9.6 runs on any of them; Kotlin ships with AGP, not as a separate plugin) |
| Android SDK | Platform 37, Build-Tools 36 |
| Android Studio | Any release that supports AGP 9.4 — optional, Gradle CLI is enough |

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

If `./gradlew` fails with a message that is just a version number, the JDK running
Gradle is outside the supported range:

```bash
java -version                                    # check
export JAVA_HOME=/path/to/jdk                    # macOS/Linux
$env:JAVA_HOME = "C:\path\to\jdk"                 # Windows PowerShell
```

The build also needs `ANDROID_HOME`, or `sdk.dir` in `local.properties`, pointing at
an Android SDK that has platform 37 installed.

## Project layout

```
app/src/main/kotlin/com/ebookreader/
├── data/
│   ├── epub/          EPUB container, OPF and NCX parsing; chapter extraction; visible text
│   ├── convert/       TXT, Markdown, FB2, MOBI/AZW3 to EPUB; CBR to CBZ
│   ├── library/       Import, covers, format dispatch
│   ├── opds/          OPDS catalogue parsing and downloads
│   ├── readaloud/     Text-to-speech controller and its media service
│   ├── reminders/     The reading-goal reminder
│   ├── sync/          Folder sync
│   ├── backup/        Stats backup file
│   ├── local/         Room database, entities and DAOs
│   └── repository/    Repository implementations
├── domain/
│   ├── model/         Domain models, reading-session tracking
│   └── repository/    Repository interfaces
├── presentation/
│   ├── library/       Library, import, book options
│   ├── reader/epub/   WebView-based EPUB reader: pages, page script, reader stylesheet, Aa sheet
│   ├── reader/pdf/    PdfRendererView-based PDF reader
│   ├── reader/comic/  Comic reader
│   ├── catalog/       Online catalogues
│   ├── widget/        Home-screen widget
│   ├── settings/      Settings
│   ├── stats/         Statistics and history
│   └── common/        Design tokens (Bindery*), fore-edge, theme and shared helpers
└── di/                Hilt modules
```

Architecture is MVVM over a repository layer: Compose screens observe a `StateFlow` of immutable UI state from a `ViewModel`, which talks only to repository interfaces defined in `domain`. Room and DataStore sit behind those interfaces.

## Tech stack

| Concern | Library |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| Dependency injection | Hilt |
| Persistence | Room (books, bookmarks, sessions, highlights, collections, full-text index), DataStore (settings) |
| Images | Coil |
| PDF rendering | [Pdf-Viewer](https://github.com/afreakyelf/Pdf-Viewer) |
| CBR comics | [junrar](https://github.com/junrar/junrar) |
| Read aloud | Platform `TextToSpeech`, `MediaSession` |
| Sync folder | AndroidX DocumentFile over the Storage Access Framework |
| EPUB rendering | WebView with a custom parser; book and fonts served through AndroidX WebKit's `WebViewAssetLoader` |
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

Unit tests run on the JVM with `./gradlew testDebugUnitTest`, and on every pull request. The
EPUB parser tests build real EPUB files on the fly and parse them with kXML, the pull parser
Android itself uses.

## License

MIT — see [LICENSE](LICENSE).

Bundled fonts are under the SIL Open Font License 1.1; their licence texts ship in the app at
`app/src/main/assets/licenses/`:

- [Literata](https://github.com/googlefonts/literata) — trimmed to Latin, Greek and Cyrillic and to
  weights 400–700 to keep the APK small
- [Inter Tight](https://github.com/rsms/inter) — trimmed the same way
- [IBM Plex Mono](https://github.com/IBM/plex) Medium — unmodified, as its licence reserves the name
- [Bitter](https://github.com/solmatas/BitterPro) — trimmed the same way; the reserved name is
  "Bitter Pro", which is not used
- [Atkinson Hyperlegible](https://www.brailleinstitute.org/freefont/) — unmodified
- [OpenDyslexic](https://opendyslexic.org) — unmodified, as its licence reserves the name

Literata's italic, used only by the reader, is also fixed at a text optical size to halve its
weight; Charis SIL was left out because its reserved name rules out trimming its 3 MB.
