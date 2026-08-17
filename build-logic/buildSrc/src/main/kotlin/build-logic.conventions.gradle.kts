import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Shared conventions for build-logic's own projects (root, :build-common, :build-test-support,
// :build-ktlint). Applied by each project individually, rather than from the root via
// `allprojects`/`subprojects`, so no project reaches into another project's mutable task
// container at configuration time.

// Treat Kotlin compiler warnings as errors, matching the Bazel -Werror.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors = true
    }
}
