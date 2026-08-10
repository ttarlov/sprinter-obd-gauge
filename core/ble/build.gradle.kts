plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Android library: implements ObdLink over BLE/GATT. Depends on :core:model only.
// Hilt annotations are permitted here per DECISIONS.md D2 (the only core module besides :app).
android {
    namespace = "com.revel.obdgauge.ble"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // api: BleObdLink is an ObdLink, and its state flow emits LinkState/LinkError to :app.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // Remembered-device address persistence (OBD-17 fast path).
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Test scope only, and intentional: :core:testing's charter is "shared fakes and fixtures
    // consumed by every other module's tests" (core/testing/MODULE.md). The ":core:model only"
    // rule governs the production graph — see the rejected finding in reviews/OBD-17-round1.md.
    testImplementation(project(":core:testing"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}
