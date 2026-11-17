plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.dnsly"
    compileSdk = 37
    // Same NDK cargo-ndk links with (docker/Dockerfile); AGP uses it to strip the .so files.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.dnsly"
        minSdk = 26
        // Not 37 yet: targeting Android 17 needs the runtime local-network permission to reach the Pi-hole.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Builds the Rust core (../../core) into src/main/jniLibs (gitignored) for the ABIs above.
// Needs cargo-ndk and ANDROID_NDK_HOME: the Docker image has both.
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    workingDir = rootDir.resolve("../core")
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "26",
        "-o", file("src/main/jniLibs").absolutePath, "build", "--release",
    )
}
// Only packaging needs the .so files, so unit tests (testDebugUnitTest) skip the Rust build.
tasks.matching { it.name.matches(Regex("merge\\w*JniLibFolders")) }.configureEach { dependsOn(cargoBuild) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("junit:junit:4.13.2")
}
