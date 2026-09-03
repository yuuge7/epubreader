# EBook Reader — Modification Proposals

A review of the current codebase (v1.5, `versionCode 4`) with concrete proposals: a design
direction for the UI, feature gaps, correctness issues found while reading the source, and
platform work that is now blocking.

Every claim below is anchored to a file and line so it can be checked rather than taken on trust.

---

## 0. Honest read of where the app is

The engineering is better than the surface suggests. The reading-position logic, the session
tracker, the migration policy in `DatabaseModule.kt`, and the Zip-Slip guard in `EpubParser.kt`
are all careful work with comments that explain *why*, not *what*. Several of the hardest bugs in
a reader — position drift on reopen, double-counted session time, backgrounded time counted as
reading — are already solved deliberately.

The UI is the opposite. It is Material 3 with the defaults left in place: `Typography()`
unmodified, dynamic color on, `Card` + `elevation 2.dp`, `FilterChip` rows, five icon buttons per
app bar, and `LinearProgressIndicator` used for reading progress. It works, and it looks like
every other Compose app built this year. The app has no identity of its own, and on Android 12+
it literally borrows one from the user's wallpaper.

So: the largest available win is not more features. It is giving the reading surface and the
library a point of view, and moving the reading controls to where reading happens.

---

## 1. Design contract

Before any pixels, the decisions everything else derives from.

**Primary user.** Someone reading long-form on a phone, mostly at night, mostly one book at a
time, offline, with no account and no cloud.

**The one action that matters.** Resume the book you were reading, in one tap, from a cold start.
Everything else on the home screen is secondary to that.

**What the product actually measures.** Not pages — *time*. `totalReadingSeconds`,
`reading_sessions`, per-book and per-month totals are the app's distinctive data. The design
should make time visible, because that is what this app knows and the big commercial readers do
not show you as well.

**The object.** A book you own, on a device that is not connected to anything. The vernacular to
draw from is bookbinding — the spine, the fore-edge, the ribbon marker, cloth boards, gilt edges,
the colophon — not "content cards."

### Direction: *Fore-edge*

One idea, applied consistently: **a book's progress is a physical property of the object, not a
progress bar attached to it.**

Draw the read portion of every book as its fore-edge — a 4 dp vertical band down the right side
of the cover, filled from the bottom, ruled with hairlines so it reads as a stack of page edges.
The same component becomes the reader's scrubber. Progress is then literally the same object in
the library, in the continue-reading hero, and in the reader. One `Canvas` composable, four
placements, zero new concepts for the user to learn.

Two supporting motifs, and no more:

- **Ribbon.** Favourite and bookmark are cloth ribbon tabs hanging from the top edge of a cover.
  In the reader, bookmarks appear as ribbon marks at their positions on the fore-edge scrubber.
  This replaces the current hardcoded red heart (`LibraryScreen.kt:426`), which sits on top of
  cover art with no scrim and disappears on any light cover.
- **Gilt.** A finished book gets a thin foil top edge. Stats achievements use the same foil. It
  is used in exactly two places; if a third appears, cut it.

This is deliberately *not* the cream-paper-and-serif treatment that every AI-designed book app
converges on. That look is the default answer for "book app," which makes it the wrong one here.
Fore-edge is defensible because it encodes real data, and it is cheap to build.

### Tokens

Replace dynamic color entirely. `EBookReaderTheme.kt:82-88` currently prefers
`dynamicLightColorScheme`/`dynamicDarkColorScheme` on API 31+, which means the three hand-built
schemes above them are dead on most devices and the app has no consistent look. It is also the
direct cause of the contrast bug in §6.

**Light — "Paper"**

| Token | Hex | Role |
|---|---|---|
| `paper` | `#FBF9F4` | window background |
| `page` | `#FFFFFF` | raised surfaces, cards |
| `ink` | `#171A1F` | primary text |
| `graphite` | `#6A6E77` | secondary text, metadata |
| `rule` | `#E2DED4` | hairlines, dividers, card borders |
| `bookcloth` | `#7A3033` | accent: progress fill, active state, primary action |
| `foil` | `#A98548` | finished / achievement only |

**Dark — "Night"**

| Token | Hex | Role |
|---|---|---|
| `paper` | `#0F1013` | window background |
| `page` | `#16181C` | raised surfaces |
| `ink` | `#DCD8D0` | primary text (never pure white — it flares at night) |
| `graphite` | `#8B8F98` | secondary text |
| `rule` | `#262930` | hairlines |
| `bookcloth` | `#C4736C` | accent |
| `foil` | `#C6A468` | finished / achievement |

**Reading surfaces are a separate token set** from the app chrome, which is the central fix to
the current theme model. Today `AppTheme.SEPIA` recolors the entire app (`EBookReaderTheme.kt:46`)
because sepia was modelled as an app theme; sepia is a *reading* preference. Split them:

- App theme: System / Light / Dark (+ AMOLED black).
- Reading theme: Paper / Sepia / Slate / Night / Black, plus user-chosen background and text
  colour and a warmth slider. Per-book override.

### Typography

`typography = Typography()` at `EBookReaderTheme.kt:106` is the single largest source of
genericness in the app. Three faces, bundled in `res/font` so the app stays fully offline:

- **Literata** — book titles and the default reading face. It was designed for e-reading, has a
  variable weight axis, and is openly licensed. On-brief rather than decorative.
- **Inter Tight** — all UI chrome. Dense, neutral, does not compete with Literata.
- **IBM Plex Mono** — numerals only: session timer, stats figures, percentages. This fixes a real
  visible defect: the session clock at `EpubReaderScreen.kt:210` re-lays-out every single second
  because Roboto's proportional digits change width. Tabular figures make it stop twitching.

Scale — eight roles, not Material's fifteen:

| Role | Face | Size / line | Tracking |
|---|---|---|---|
| `display` | Literata 600 | 34 / 39 | −0.02em |
| `title` | Literata 600 | 22 / 27 | −0.01em |
| `bookTitle` | Literata 500 | 15 / 20 | 0 |
| `body` | Inter 400 | 15 / 22 | 0 |
| `label` | Inter 500 | 13 / 18 | +0.01em |
| `eyebrow` | Inter 600 | 11 / 14 | +0.08em, uppercase |
| `numeral` | Plex Mono 500 | 20 / 20 | tabular |
| reading | user's choice | user's choice | user's choice |

### Surface treatment

Drop card elevation. `CardDefaults.cardElevation(defaultElevation = 2.dp)`
(`LibraryScreen.kt:377`) puts a grey shadow on a warm paper ground, which reads as muddy. Use a
1 dp `rule` hairline on `page` instead. Printed, not floating. It also removes the overdraw.

### Motion

- Controls in/out: 120 ms fade. Currently `fadeIn() + slideInVertically()` with defaults.
- Chapter change: 160 ms cross-fade over the WebView. Today the chapter reload flashes white —
  `loadDataWithBaseURL` is called on every chapter change (`EpubReaderScreen.kt:645`) with no
  transition covering it.
- Cover → reader: shared-element transition on the cover (Compose 1.7 shared transitions).
- Fore-edge fill animates on progress change.
- Honour `ANIMATOR_DURATION_SCALE == 0` and skip all of it.

---

## 2. Slop audit — what reads as default today

| # | Finding | Where |
|---|---|---|
| S1 | Material default typography, untouched | `EBookReaderTheme.kt:106` |
| S2 | Dynamic color overrides the three hand-built schemes on API 31+ | `EBookReaderTheme.kt:82-88` |
| S3 | Five icon buttons crammed into the library app bar (search, sort, view, stats, settings) with no hierarchy | `LibraryScreen.kt:83-110` |
| S4 | Five more in the reader app bar | `EpubReaderScreen.kt:187-238` |
| S5 | `⏱` emoji used as interface iconography in three places | `LibraryScreen.kt:457,536`, `EpubReaderScreen.kt:210` |
| S6 | Format badge paints PDF with `colorScheme.error` — error red misused as a file-type colour | `LibraryScreen.kt:404,510` |
| S7 | Hardcoded `Color.Red` heart and `Color.White` text over arbitrary cover art, no scrim | `LibraryScreen.kt:413,426` |
| S8 | `ContentScale.Crop` into a fixed `180.dp` box destroys every cover's 2:3 aspect ratio | `LibraryScreen.kt:383-386` |
| S9 | Coverless books all render the same icon + `title.take(20)` — every one looks identical | `LibraryScreen.kt:580-620` |
| S10 | Reading progress drawn as `LinearProgressIndicator`, which the platform teaches users to read as "loading" | `LibraryScreen.kt:432`, `:551` |
| S11 | Library errors render as a persistent `Card` in the layout flow, shoving content down, with no `SnackbarHost` on the screen at all | `LibraryScreen.kt:170-196` |
| S12 | Stats screen is stat-cards and text rows — a screen entirely about trends, with no chart | `StatsScreen.kt:138-200` |
| S13 | Chapter scrubber `IconButton(Modifier.size(36.dp))` — below the 48 dp minimum target | `EpubReaderScreen.kt:284,316` |
| S14 | Every nav transition is the same 300 ms fade+slide regardless of the relationship between screens | `AppNavigation.kt:32-56` |
| S15 | Reader font size lives in Settings, two screens away from reading | `SettingsScreen.kt:52-72` |

---

## 3. Screen-by-screen

### 3.1 Home (replaces Library)

```
┌────────────────────────────────────────────┐
│ Shelf                            ⌕     ⋯   │  2 actions, not 5
├────────────────────────────────────────────┤
│ ┌──────┐                                   │
│ │      │  CONTINUE                         │  eyebrow
│ │ cov  │  The Name of the Wind             │  Literata title
│ │  er ▐│  Ch. 14 · The Broken Binding      │  ▐ = fore-edge
│ └──────┘  18 min left in this chapter      │  from the user's own WPM
│           41% · 6h 12m in this book        │  tabular numerals
├────────────────────────────────────────────┤
│ All   Reading   Finished   Favourites   ⇅  │
├────────────────────────────────────────────┤
│ ┌──────┐   ┌──────┐   ┌──────┐             │
│ │ 2:3 ▐│   │ 2:3 ▐│   │ 2:3 ▐│             │
│ └──────┘   └──────┘   └──────┘             │
│  Title      Title      Title               │
│  Author     Author     Author              │
└────────────────────────────────────────────┘
```

- **L1 — Continue hero.** `getRecentBooks()` already exists end-to-end (`BookDao.kt:19` →
  `BookRepositoryImpl.kt:54` → `BookRepository.kt:11`) and is called by nothing. This is the
  one-tap resume the app is missing, and the query is already written.
- **L2 — Time remaining.** Derive words-per-minute from the user's own history
  (`reading_sessions` ÷ characters covered) and show "18 min left in this chapter" and "4h 20m
  left in the book". This is the app's differentiator and it needs no new data.
- **L3 — App bar down to two actions.** Search becomes an inline field; sort, view mode, stats
  and settings move into one overflow / bottom sheet.
- **L4 — Covers keep their aspect ratio.** `AspectRatio(2f/3f)` + `ContentScale.Fit` on a
  cloth-coloured ground. Cropping a book cover is the one thing a reader must not do.
- **L5 — Generated covers.** For coverless books, typeset the title in Literata on a binding
  colour chosen deterministically from `hash(title)` out of eight muted cloth tones, with a
  hairline frame and the author at the foot. Every book then looks like a distinct object.
- **L6 — Fore-edge instead of progress bar** on cards, rows and hero.
- **L7 — Ribbon instead of heart** for favourites; gilt top edge for finished.
- **L8 — Errors go to a `Snackbar`**, and the screen gets a `SnackbarHost`.
- **L9 — Long-press multi-select** with a contextual bar: delete, favourite, mark finished, move
  to collection. Currently long-press opens a single-book sheet only (`LibraryScreen.kt:246`).
- **L10 — Collections / shelves / series**, with "next in series" surfacing on the hero.
- **L11 — Sort direction toggle** and more keys: progress, reading time, file size. Today sort is
  ascending-only with four keys (`BookDao.kt:28-36`).
- **L12 — Book detail screen**: description, full metadata, cover replace, per-book stats, file
  info, per-book reading settings. `EpubBook.description` is declared at `EpubChapter.kt:16` and
  never populated or shown.
- **L13 — Search over book *content*,** not just title/author `LIKE` (`BookDao.kt:38`). Room FTS4
  over the extracted chapter text.

### 3.2 Reader

```
┌────────────────────────────────────────────┐
│ ←   The Name of the Wind         Aa    ⋯   │  Aa = typography sheet
│     Ch. 14 · The Broken Binding    12:04   │  tabular numerals
├────────────────────────────────────────────┤
│                                            │
│                (page text)                 │
│                                            │
├────────────────────────────────────────────┤
│ ▍▍▎▍▍▊▍▎▍▍▍▎▍▊▍▍▎▍▍▍▍▎▍▍▍▍▍▍▎▍▍▍           │  ticks sized by chapter length
│ 41%  ·  6h 12m in  ·  4h 20m left      ▮   │  ▮ = bookmark ribbon
└────────────────────────────────────────────┘
```

- **R1 — In-reader typography sheet.** The biggest single UX defect in the app right now is that
  changing font size requires leaving the book, navigating to Settings, dragging a slider, and
  navigating back (`SettingsScreen.kt:52`). An `Aa` button opening a sheet with font family, size,
  line height, margin, alignment, hyphenation, theme, brightness and warmth is table stakes.
- **R2 — Font family choice.** The reading face is hardcoded `Georgia, serif` in the injected
  stylesheet (`EpubParser.kt:204`). Offer Literata, Bitter, Charis, Atkinson Hyperlegible,
  OpenDyslexic, Inter, and "publisher's own".
- **R3 — Line height, margins, justification, hyphenation** — all currently fixed at
  `line-height: 1.7; padding: 16px` (`EpubParser.kt:204`).
- **R4 — Paginated mode.** The reader is a scrolling WebView with edge-swipe chapter changes
  (`EpubReaderScreen.kt:140-165`). Add real pagination via CSS multi-column
  (`column-width: 100vw; column-gap: 0`) with horizontal snap and a page-turn animation. Many
  readers will not use a scrolling reader at all.
- **R5 — Tap zones.** Left/right thirds turn the *page*, centre toggles controls. Today only the
  outer 18% responds, only to a drag, and only to change *chapter*.
- **R6 — Weighted progress.** Progress is `(chapterIndex + scrollFraction) / totalChapters`
  (`EpubReaderViewModel.kt:130`). Chapters differ in length by an order of magnitude, so "41%
  read" is routinely wrong by ten points or more, and a book whose first three spine items are a
  title page, a copyright page and a dedication shows ~15% before a word has been read. Weight by
  character count per chapter — the text is already in `chapterContents`.
- **R7 — Text selection actions.** Highlight (four colours), note, copy, define, translate,
  search in book, share. There is no selection handling at all today.
- **R8 — Highlights and notes** need their own table; only `bookmarks` exists, and it is
  chapter-granular.
- **R9 — Search within the book**, with a hit list and jump-to. Chapter text is already fully
  extracted into memory, so this is nearly free.
- **R10 — Quote cards.** Render a highlighted passage typeset on the app's paper with title and
  author, share as an image. A natural signature feature, directly on-brief.
- **R11 — Text-to-speech** with sentence highlighting and a media notification.
- **R12 — Auto-scroll** with speed control.
- **R13 — Per-book reading settings** override.
- **R14 — Real dark mode.** Dark is currently `body{background-color:#1a1a1a!important;
  color:#e0e0e0!important}` (`EpubReaderScreen.kt:477-483`). Any publisher stylesheet that sets a
  colour on `p`, `span` or `div` beats it, producing black-on-black passages. Needs a scoped
  override that also handles inline styles, plus `img { filter: brightness(.8) }` so figures do
  not glare.
- **R15 — Chapter preloading.** Render the next and previous chapters into offscreen WebViews so
  a chapter turn is instant instead of a full `loadDataWithBaseURL`.
- **R16 — Nested TOC.** `EpubChapter.subChapters` exists (`EpubChapter.kt:8`), is never populated
  by `parseNcx`, and is never rendered by `TableOfContentsPanel`. Books with two-level navigation
  show a flat list.
- **R17 — EPUB 3 navigation documents.** `findNcxHref` falls back to the EPUB 3 `nav` document
  (`EpubParser.kt:150-155`) but then feeds it to `parseNcx`, which only understands NCX
  `<navPoint>` elements. For an EPUB 3 book with no NCX, `parseNcx` returns empty and the TOC
  silently degrades to "Chapter 1 … Chapter N". A `nav[epub:type=toc]` parser fixes a whole class
  of modern books.
- **R18 — Immersive mode** while reading; system bars appear only with the controls.
- **R19 — Screen brightness** slider scoped to the reader, plus a warmth / blue-light overlay.
- **R20 — `keepScreenOn` should be reader-scoped.** It is set on the Activity window
  (`MainActivity.kt:36-45`), so it also keeps the screen awake in the library and in settings.
- **R21 — Landscape / tablet two-column** reading layout.
- **R22 — Bookmarks with position.** `getBookmarkByPage(bookId, page)` implies one bookmark per
  chapter. Store `scrollFraction` and an anchor so several positions in a long chapter can be
  marked, and show a text excerpt in the list.
- **R23 — Reading ruler / focus dimming** of the surrounding paragraphs.

### 3.3 PDF reader

- **P1 — Text selection, search, and outline.** None of the three exist. The bundled
  `io.github.afreakyelf:Pdf-Viewer` renders bitmaps only.
- **P2 — Replace the viewer.** The current one needs a twelve-iteration, 400 ms retry loop just
  to restore a page (`PdfReaderScreen.kt:83-93`). Moving to `android.graphics.pdf.PdfRenderer`
  directly, or to AndroidX `pdf-viewer`, removes that hack and unlocks P1.
- **P3 — Sub-page position.** PDF progress is page-granular; `scrollFraction` is unused for PDFs.
- **P4 — Cover thumbnails for PDFs.** PDF books never get a cover — `readMetadata` is EPUB-only
  (`LibraryViewModel.kt:219`), so every PDF is a placeholder tile. Rendering page 1 once at import
  fixes the single biggest visual hole in the grid.
- **P5 — Page thumbnail grid** navigator.
- **P6 — Night mode / invert** for PDF pages.
- **P7 — Crop margins** and reflow.

### 3.4 Stats

- **T1 — A year heat map**, drawn as page-edge marks so it reuses the fore-edge motif rather than
  importing a GitHub contribution grid.
- **T2 — Streaks** — current and longest.
- **T3 — Goals**: minutes per day, books per year, with progress and a gentle reminder.
- **T4 — Weekly and monthly bar charts.** Currently the whole screen is stat cards and text rows.
- **T5 — Per-book detail**: sessions over time, pace, projected finish date.
- **T6 — Year in reading** summary card, shareable as an image.
- **T7 — Books-finished timeline.**
- **T8 — CSV export**, alongside the existing JSON.
- **T9 — `ReadingStats` and its `dailyStats` map are dead** (`ReadingStats.kt:13-16`) — either
  build the daily view they were written for, or delete them.

### 3.5 Settings

- **T10 — Split into Reading / Library / Data / About.** Move all typography into the reader (R1)
  and leave Settings for things that are genuinely global.
- **T11 — Storage screen**: space used by books, covers and the extraction cache, with a clear
  action. `EpubParser.clearCache()` exists (`EpubParser.kt:252`) and is never called — the cache
  grows forever.
- **T12 — Full backup / restore**, not just stats: library, progress, bookmarks, highlights and
  settings in one file.
- **T13 — Optional sync** to a user-owned destination (Drive, WebDAV, a Syncthing folder). Keeps
  the offline-first promise while solving multi-device.

---

## 4. Feature ideas beyond the current scope

- **F1** — More formats: `.cbz` / `.cbr` comics, `.txt`, `.md`, `.fb2`, `.azw3`, `.mobi`.
- **F2** — Bulk import: pick a folder, import everything in it.
- **F3** — OPDS catalogue browsing (Standard Ebooks, Project Gutenberg) as an import source.
- **F4** — Duplicate detection by content hash, not file path. Today the same book imported under
  two filenames becomes two library entries (`LibraryViewModel.kt:246`).
- **F5** — `ACTION_SEND` share target. The manifest handles `ACTION_VIEW` only
  (`AndroidManifest.xml:33-58`), so "Share → EBook Reader" from a browser or mail client does
  nothing.
- **F6** — Dynamic app shortcuts for the last three books.
- **F7** — Home-screen widget: continue reading, or the current streak.
- **F8** — Quick Settings tile that opens the current book.
- **F9** — Offline dictionary lookup, and on-device translation via ML Kit.
- **F10** — Auto night switch at sunset.
- **F11** — Reading reminders / streak notifications.
- **F12** — Import metadata from filename patterns (`Author - Title (Year)`).
- **F13** — Let the user set a cover from an image when the book has none.
- **F14** — Read-aloud sleep timer.

---

## 5. Architecture, performance and correctness

- **A1 — `AppSettings` is one blob.** Every change rewrites all eight DataStore keys
  (`SettingsRepositoryImpl.kt:50-61`) and re-emits the whole object, which recomposes the
  `NavHost` and the reader. Split into scoped flows, or at minimum apply `distinctUntilChanged`
  per field.
- **A2 — Font-size slider writes on every pixel.** `onValueChange = { onSettingsChange(...) }`
  (`SettingsScreen.kt:57`) triggers a DataStore write and a full EPUB chapter re-render per frame
  of the drag. Use `onValueChangeFinished`.
- **A3 — Whole-book extraction into memory.** `parseAndExtract` reads every chapter file into a
  `Map<String, String>` (`EpubParser.kt:60-71`). `android:largeHeap="true"`
  (`AndroidManifest.xml:23`) is masking this. Load chapters lazily and cache a window.
- **A4 — Import parses the entire book twice.** `readMetadata` calls the full `parseAndExtract`
  just to read title and author (`EpubParser.kt:232`). A metadata-only path that reads
  `container.xml` and the OPF takes a few hundred milliseconds instead of several seconds on a
  large book.
- **A5 — Stats aggregate in memory on every emission.** `StatsViewModel` groups *all* sessions
  with a fresh `Calendar` allocation per session, four times over, on every database change
  (`StatsViewModel.kt:45-125`). After a year of daily reading that is thousands of rows
  re-bucketed on each write. Move the aggregation into SQL.
- **A6 — Deleting a book leaks its files.** `deleteBook` removes the row only
  (`BookRepositoryImpl.kt:69`). The copied file in `filesDir/books`, the cover in
  `filesDir/covers`, and the extraction directory in `cacheDir` all survive. Bookmarks cascade
  correctly; files do not.
- **A7 — No tests at all.** `testInstrumentationRunner` is declared (`build.gradle.kts:33`) but
  `app/src` contains only `main`. The highest-value targets are the ones with the subtlest logic:
  `EpubParser` (malformed OPF, BOM, EPUB 3 nav, Zip-Slip), `ReadingSessionTracker`
  (`takeUnsavedSeconds` must never double-count), `Book.readingProgress`, and the v3→v4 migration.
- **A8 — CI never runs tests.** `.github/workflows/build.yml` assembles the debug APK and stops.
- **A9 — `isMinifyEnabled = false`** in release (`build.gradle.kts:50`). No R8, no shrinking; the
  shipped APK is ~15 MB for what is mostly Compose and Room.
- **A10 — No baseline profile**, so cold start pays the full JIT cost on a Compose-heavy app.
- **A11 — No `SavedStateHandle`.** Reader ViewModels take `bookId` through `loadBook(id)` rather
  than from saved state, so process death loses the argument.
- **A12 — Two unused dependencies.** `accompanist-permissions` and `accompanist-systemuicontroller`
  are declared in `build.gradle.kts:127-128`; neither appears anywhere in `app/src`. The
  system-ui-controller library is deprecated in any case.
- **A13 — Dead code to remove or wire up**: `getRecentBooks` (three layers, no caller),
  `getBookCount`, `getReadingTimeInRange`, `ReadingStats`, `EpubChapter.subChapters`,
  `EpubBook.description`, `EpubParser.clearCache`.

---

## 6. Bugs and platform issues

- **B1 — `targetSdk = 34` blocks Play Store updates.** Google Play has required API 35 for new and
  updated apps since 31 August 2025 (`build.gradle.kts:29`). This is a hard shipping blocker, not
  a nice-to-have.
- **B2 — Contrast failure in dynamic light themes.** The format badge paints `Color.White` text on
  `colorScheme.primary` (`LibraryScreen.kt:404-415`). With dynamic color derived from a pale
  wallpaper, `primary` can be light enough that white text falls below 3:1. Using `onPrimary` — or
  removing dynamic color per §1 — fixes it.
- **B3 — Three unnecessary permissions.** `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and
  `READ_MEDIA_AUDIO` (`AndroidManifest.xml:7-9`) are requested by an app that imports through the
  Storage Access Framework and needs none of them. They are a privacy smell and a Play Console
  declaration liability.
- **B4 — `allowBackup="true"` with no backup rules** (`AndroidManifest.xml:16`). Every imported
  book file in `filesDir/books` is eligible for cloud backup. Add `dataExtractionRules` that
  include the database and settings and exclude the book files and cache.
- **B5 — WebView hardening.** JavaScript is enabled with a `@JavascriptInterface` bridge for
  book-supplied HTML (`EpubReaderScreen.kt:576-635`), so an EPUB can run script. Set
  `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`, and
  `blockNetworkLoads = true` — the last one also stops a book from phoning home about what you
  read, which matters for an app whose pitch is "offline-first".
- **B6 — System font scale ignored in the reader.** Font size is injected as `px`
  (`EpubReaderViewModel.kt:268`), so a user who has set a large system font gets no effect inside
  the book. Convert through `density.fontScale`.
- **B7 — `LocalLifecycleOwner` from `androidx.compose.ui.platform`** is deprecated in current
  lifecycle versions; it is used in both readers.
- **B8 — Toolchain is a year and a half behind.** Kotlin 1.9.23, AGP 8.3.2, Compose BOM
  2024.04.01. Moving to Kotlin 2.x with the Compose compiler Gradle plugin also unlocks strong
  skipping mode, which measurably helps the recomposition problems in A1.
- **B9 — Favourite toggle has no state in its accessibility label.** `contentDescription =
  "Favorite"` regardless of state (`LibraryScreen.kt:424`); TalkBack cannot tell you whether the
  book is favourited.
- **B10 — Touch targets below 48 dp** in the chapter scrubber (`EpubReaderScreen.kt:284,316`).

---

## 7. Accessibility

- **X1** — Bundle Atkinson Hyperlegible and OpenDyslexic as reading faces (R2).
- **X2** — Respect the system font scale (B6).
- **X3** — State-aware `contentDescription` on every toggle (B9).
- **X4** — 48 dp minimum on every interactive target (B10).
- **X5** — The WebView's document-level `click` handler (`EpubReaderScreen.kt:606`) intercepts
  taps before TalkBack; expose an explicit "Show controls" action instead.
- **X6** — Semantic headings and reading order for the chapter content.
- **X7** — Verify contrast for all seven tokens in both themes, and pin them so a future
  dynamic-color change cannot regress it.
- **X8** — Honour reduced-motion for the page-turn and transition work in §1.

---

## 8. Suggested order

**Phase 1 — unblock and stop the bleeding**
B1 (targetSdk 35), B3, B4, B5, A6 (file leak on delete), A2 (slider write storm), A12 and A13
(remove dead weight).

**Phase 2 — the design system**
Tokens and typography from §1, drop dynamic color, split app theme from reading theme, build the
fore-edge component. Nothing else changes yet; every screen inherits.

**Phase 3 — the reading experience**
R1 (in-reader typography sheet), R2 and R3, R5 (tap zones), R6 (weighted progress), R14 (real
dark mode), R15 (preloading).

**Phase 4 — the library**
L1 and L2 (continue hero with time remaining), L4 and L5 (covers), L6 and L7 (fore-edge, ribbon),
L8, L9, P4 (PDF covers).

**Phase 5 — depth**
R7–R10 (selection, highlights, search, quote cards), T1–T6 (stats with actual visualisation), L10
(collections), L13 (full-text search).

**Phase 6 — reach**
R11 (TTS), F1 (formats), F3 (OPDS), T13 (sync), F7 (widget).

Tests (A7) and CI (A8) are not a phase; add them alongside whichever phase touches the code they
would cover, starting with `EpubParser` in Phase 3.
