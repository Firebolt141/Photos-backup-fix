plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace   = "com.firebolt141.ubertrag"
    compileSdk  = 37

    defaultConfig {
        applicationId   = "com.firebolt141.ubertrag"
        minSdk          = 26
        targetSdk       = 36
        versionCode     = 5
        versionName     = "5.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// AGP 9 compiles Kotlin itself (no separate kotlin-android plugin).
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.core.ktx)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore (persist folder URIs)
    implementation(libs.datastore.preferences)

    // ExifInterface (read DateTimeOriginal from images)
    implementation(libs.exifinterface)

    // DocumentFile (SAF wrapper for writing to external drives)
    implementation(libs.documentfile)

    // Coroutines
    implementation(libs.coroutines.android)

    // Unit tests (PhotoLogic is pure Kotlin; org.json replaces Android's stubbed copy on the JVM)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
