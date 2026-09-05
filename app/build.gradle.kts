plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
}

// OBD-45: dual-channel builds. `-Pchannel=dev` is a lightweight property switch (NOT a new
// flavorDimension — the demo/prod x debug/release matrix stays exactly as-is) that applies
// `applicationIdSuffix ".dev"` and swaps the launcher label to "OBD Gauge Dev", so a
// dev-channel sideload installs ALONGSIDE the main-channel build instead of overwriting it
// (docs/05-local-workflow.md §D5). When the property is absent — every build today, and every
// build that doesn't opt in — `channel` is null, the `if` blocks below don't execute, and
// defaultConfig is byte-identical to pre-OBD-45. That's the acceptance criterion, not
// incidental: see tools/channel-build.sh for the two invocations this feeds.
val channel: String? = providers.gradleProperty("channel").orNull

android {
    namespace = "com.revel.obdgauge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.revel.obdgauge.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default placeholder resolves to the literal manifest text the app used before
        // OBD-45 (`android:label="${appLabel}"` in AndroidManifest.xml substitutes this in
        // verbatim, then AAPT resolves the resulting `@string/app_name` reference as always)
        // — so a build without `-Pchannel=dev` is unaffected.
        manifestPlaceholders["appLabel"] = "@string/app_name"

        if (channel == "dev") {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "@string/app_name_dev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // OBD-12: `demo`/`prod` product flavors, one dimension. `demo` DI-wires
    // FakeVehicleDataSource (src/demo/) against Scenario.GRADE_CLIMB, zero Bluetooth
    // permissions required. `prod` DI-wires a stub VehicleDataSource (src/prod/) that sits
    // Disconnected with empty readings — the real ObdLink/:core:protocol chain arrives in
    // OBD-25. See app/MODULE.md and the HARD CONSTRAINT in issues/OBD-12.md:
    // `:core:testing` must never reach the prod runtime classpath (enforced below by scoping
    // its dependency to `demoImplementation`).
    flavorDimensions += "environment"
    productFlavors {
        create("demo") {
            dimension = "environment"
        }
        create("prod") {
            dimension = "environment"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // OBD-70: BuildConfig.VERSION_NAME/FLAVOR/APPLICATION_ID for the recorder's self-describing
        // CSV header comment (`# app: <versionName> (flavor=..., channel=...)`) — no other feature
        // in this app has needed generated build config before now.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Robolectric (OBD-10) needs the merged manifest/resources on the unit-test
        // classpath — it resolves `androidx.activity.ComponentActivity` (compose-ui-test's
        // default test host, registered by `ui-test-manifest`) from there.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // :core:model is the frozen contract surface both flavors build against. :core:testing
    // (FakeVehicleDataSource) is `demo`-only by design (OBD-12 HARD CONSTRAINT) — real
    // ObdLink -> VehicleDataSource wiring (:core:ble, :core:protocol) for `prod` arrives with
    // OBD-25; see app/MODULE.md.
    implementation(project(":core:model"))
    "demoImplementation"(project(":core:testing"))
    // OBD-19: the debug-only "OBD Console" raw AT-command REPL needs the real BleObdLink.
    // Still `debugImplementation` because the console must exist in demoDebug too, where
    // :core:ble is otherwise absent.
    debugImplementation(project(":core:ble"))
    // OBD-25: the `prod` dashboard flow IS the real chain (BleObdLink -> RealVehicleDataSource),
    // so :core:ble and :core:protocol are now `prod` runtime dependencies in BOTH build types —
    // `prodRelease` included, which is the point: a release van build with no BLE would be an
    // app that cannot read an engine. The fence that stays intact is the one that always
    // mattered: `demoRelease` sees neither module, and `:core:testing` reaches neither prod
    // variant. Verify with
    // `:app:dependencies --configuration demoReleaseRuntimeClasspath | grep -i "core:ble"`
    // (expect empty) and
    // `:app:dependencies --configuration prodReleaseRuntimeClasspath | grep -Ei "junit|core:testing"`
    // (expect empty). :core:ble's OWN debug/release fence (OBD-48's LogcatTrafficLog vs
    // TrafficLog.NONE, enforced per build type inside the module and verified at the AAR's
    // classes.jar — see core/ble/MODULE.md) is unaffected by which :app configuration consumes
    // it, and is re-verified in this issue's report.
    "prodImplementation"(project(":core:ble"))
    "prodImplementation"(project(":core:protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // OBD-21 settings persistence — own DataStore file, see settings/di/SettingsModule.kt.
    implementation(libs.androidx.datastore.preferences)

    // OBD-79 maintenance tracker persistence — flavor-common (:core:model only, no BLE/protocol).
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    // Verified empirically against `main`: the plain (non-variant-aware) `detekt` task's
    // default source set is `src/main/kotlin` only — test sources were never in its scope
    // (pre-existing behavior, not something OBD-11/12 changes). OBD-12 adds two more
    // production source sets, `src/demo/kotlin` and `src/prod/kotlin` (DI wiring + the prod
    // stub), which need the same coverage `src/main/kotlin` already had — listed explicitly
    // here since the default doesn't know about flavor source sets at all.
    source.setFrom("src/main/kotlin", "src/demo/kotlin", "src/prod/kotlin")
}

// Unit tests run on the debug variant only: the suite is Robolectric/Roborazzi-based and
// Robolectric cannot instrument release-variant activities, while running the same JVM
// tests twice per gate adds time and no signal.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}
