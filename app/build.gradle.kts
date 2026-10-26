import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

// Release signing credentials live in keystore.properties, which is never committed.
// CI writes the file from repository secrets; contributors without it still get a
// working debug build, and `assembleRelease` simply produces an unsigned APK.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null &&
    rootProject.file(keystoreProperties.getProperty("storeFile")).exists()

android {
    namespace = "com.ebookreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ebookreader"
        minSdk = 26
        // Play requires the current target level for updates; staying behind it blocks
        // publishing entirely, so this must not drift again.
        targetSdk = 36
        // versionCode must increase for every published build; versionName drives the
        // release tag and title (see .github/workflows/release.yml).
        versionCode = 6
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks, optimises and obfuscates; unused resources go too. The keep
            // rules in proguard-rules.pro cover what is reached by name at run time.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Exposes VERSION_NAME so the About screen cannot drift from the real version.
        buildConfig = true
    }

    // The exported Room schemas, for MigrationTestHelper.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9 supplies Kotlin itself, so this replaces the old android.kotlinOptions block.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room schema JSON is checked in so future migrations can be diffed against v4.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/** Used by the release workflow to derive the tag and release name. */
tasks.register("printVersionName") {
    val versionName = android.defaultConfig.versionName
    doLast { println(versionName) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Coil
    implementation(libs.coil.compose)

    // PDF Viewer — Maven Central, no JitPack required
    implementation("io.github.afreakyelf:Pdf-Viewer:2.1.1")

    // RecyclerView (for PDF scrolling helper)
    implementation(libs.androidx.recyclerview)

    // Serves the open book and the reading fonts to the EPUB WebView from one origin
    implementation(libs.androidx.webkit)
    implementation(libs.junrar)
    implementation(libs.androidx.documentfile)

    // Installs the baseline profile on devices that do not get it from the Play Store.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.org.json)

    // On-device tests: the database migrations on a real SQLite, and the app's main
    // screens driven through Compose.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    constraints {
        implementation(libs.kotlinx.serialization.core) {
            because("room-testing's schema reader needs 1.8, and the tests share the app's classpath")
        }
    }
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
