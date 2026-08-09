plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Pure Kotlin/JVM module: zero Android dependencies, zero DI annotations. See DECISIONS.md D2.
// Depends on :core:model only. Shared fakes/fixtures consumed by every other module's tests.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // api: PidIds/contract types appear in this module's public fake signatures.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}
