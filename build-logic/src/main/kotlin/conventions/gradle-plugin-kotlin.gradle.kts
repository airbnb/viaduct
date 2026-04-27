/**
 * Convention for Gradle plugin projects under `gradle-plugins/`.
 *
 * This replaces `conventions.kotlin` for plugin-authoring projects. It deliberately omits
 * the Treehouse-constrained settings that `conventions.kotlin` applies:
 *   - No apiVersion / languageVersion pinning to 1.8
 *   - No `-Xcontext-receivers`
 *   - No `idea` plugin
 *   - No Viaduct internal opt-in annotations
 *
 * IMPORTANT: a Kotlin plugin must be applied BEFORE this convention. For `kotlin-dsl`
 * plugin projects, apply `kotlin-dsl` first. For plain Kotlin libraries (e.g. `common`),
 * apply `org.jetbrains.kotlin.jvm` first. This convention configures Kotlin but does not
 * provide it.
 *
 * The "Kotlin Gradle plugin was loaded multiple times" warning persists because
 * `gradle-plugins/settings.gradle.kts` includes `build-logic` in its `pluginManagement`,
 * and `build-logic` carries KGP 1.9.24 alongside the embedded Kotlin from `kotlin-dsl`.
 * Eliminating the warning would require removing `build-logic` from `gradle-plugins`'s
 * `pluginManagement`, which would also lose access to `conventions.viaduct-publishing`,
 * `buildroot.orchestration`, and other valuable conventions. The warning is cosmetic and
 * does not affect build correctness.
 */
package conventions

plugins {
    java // idempotent — both kotlin-dsl and kotlin-jvm already apply this
    id("conventions.jacoco")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

dependencies {
    testImplementation(libs.findLibrary("kotlin-test").get())
    testImplementation(libs.findLibrary("junit").get())

    testRuntimeOnly(libs.findLibrary("junit-engine").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
