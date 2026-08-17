/**
 * Convention for Gradle plugin projects under `gradle-plugins/`.
 *
 * This replaces `conventions.kotlin` for plugin-authoring projects. It deliberately omits
 * the Treehouse-constrained settings that `conventions.kotlin` applies:
 *   - No apiVersion / languageVersion pinning
 *   - No `idea` plugin
 *
 * It DOES opt in to `viaduct.apiannotations.InternalApi`: the gradle-plugins sources mark their
 * own cross-subproject internals `@InternalApi` (a `@RequiresOptIn` marker), so consuming them
 * within these plugin modules must opt in — otherwise the opt-in warnings would become errors
 * once the gradle-plugins build enforces `-Werror`.
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

import buildroot.registerForOrchestrationAggregate
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java // idempotent — both kotlin-dsl and kotlin-jvm already apply this
    id("conventions.jacoco")
    id("conventions.test-retry")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

dependencies {
    testImplementation(libs.findLibrary("junit").get())

    testRuntimeOnly(libs.findLibrary("junit-engine").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

registerForOrchestrationAggregate("build", "build")
registerForOrchestrationAggregate("check", "check")
registerForOrchestrationAggregate("clean", "clean")
registerForOrchestrationAggregate("classes", "classes")
registerForOrchestrationAggregate("test", "test")
registerForOrchestrationAggregate("testClasses", "testClasses")

// A Kotlin plugin is applied before this convention, so the KotlinCompile tasks exist. This block:
//  - opts in to the Viaduct stability markers used by gradle-plugins' own sources (see header);
//  - treats Kotlin compiler warnings as errors, matching the Bazel -Werror.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        optIn.add("viaduct.apiannotations.InternalApi")
        allWarningsAsErrors = true
    }
}
