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
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.junit)
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.turbine)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}
