# EBookReader

A modern, feature-rich Android application for reading and managing your digital library of PDF and EPUB books. Built with Jetpack Compose and modern Android development practices.

## 🚀 Features

- **Multi-Format Support**: Seamlessly read both PDF and EPUB files.
- **Smart Library**: 
    - Automatic progress tracking (last page read).
    - Grid and List view options.
    - Advanced sorting (Title, Author, Date Added, Last Read).
    - Filtering by format (PDF/EPUB), status, or favorites.
- **Reader Experience**:
    - **Themes**: Light, Dark, and Sepia modes.
    - **Navigation**: Table of contents support and vertical/horizontal scroll options.
    - **Bookmarks**: Save important pages with optional notes.
    - **Reading Timer**: Track your current session and total reading time per book.
- **Statistics Dashboard**:
    - Monthly and Yearly reading time summaries.
    - Reading leaderboards (Most read books this month/all-time).
    - Detailed session history and yearly archives.
- **Onboarding**: Beautiful introduction flow for new users.

## 🛠 Tech Stack

- **UI**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (100% Declarative UI)
- **Architecture**: MVVM with Clean Architecture principles.
- **Dependency Injection**: [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)
- **Database**: [Room](https://developer.android.com/training/data-storage/room) (SQLite)
- **Networking/Image Loading**: [Coil](https://coil-kt.github.io/coil/)
- **Navigation**: [Navigation Compose](https://developer.android.com/jetpack/compose/navigation)
- **PDF Engine**: [Pdf-Viewer](https://github.com/afreakyelf/Pdf-Viewer)
- **Build System**: Gradle with Version Catalogs (`libs.versions.toml`)

## 💻 Setup & Installation

To continue development or build the project on a new machine, follow these steps:

### Prerequisites
1. **JDK 17**: Ensure you have Java Development Kit 17 installed.
2. **Android Studio**: Recommended version is **Hedgehog (2023.1.1)** or newer.
3. **Android SDK**: Compile SDK 34 and Target SDK 34 are required.

### Getting Started
1. **Clone the repository**:
   ```bash
   git clone <your-repository-url>
   ```
2. **Open in Android Studio**:
   - Launch Android Studio and select **Open**.
   - Navigate to the root folder of the project and click **OK**.
3. **Gradle Sync**:
   - Once the project opens, it should automatically trigger a Gradle sync. 
   - If not, go to `File -> Sync Project with Gradle Files`.
4. **Build and Run**:
   - Select the `app` module in the run configurations.
   - Choose an emulator or a physical device (Minimum SDK is 26 / Android 8.0).
   - Click the **Run** button (Green Play icon).

## 📂 Project Structure

- `app/src/main/kotlin`:
    - `data`: Database entities, DAOs, and repository implementations.
    - `domain`: Domain models and repository interfaces (The "Source of Truth").
    - `presentation`: UI layer including Screens, ViewModels, and Compose components.
    - `di`: Hilt modules for dependency injection.
- `app/src/main/res`: Android resources (Icons, XMLs, Values).
- `gradle/libs.versions.toml`: Centralized dependency management.

## 📝 Development Notes

- **Database Migrations**: The project uses Room. If you modify any entity in `data/local/entity`, remember to increment the version in `AppDatabase.kt`.
- **EPUB Parsing**: EPUB handling is managed via a custom parser in the `data/epub` package.
- **Styling**: Global themes are defined in `presentation/common/EBookReaderTheme.kt`.

---
*Happy Reading!* 📖
