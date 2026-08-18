import org.gradle.testing.jacoco.tasks.JacocoReport
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

// :build-common, :build-test-support and :build-ktlint all apply `jacoco` for the same JUnit 5 +
// coverage-report setup; the root project doesn't apply `jacoco`, so this is a no-op for it.
pluginManager.withPlugin("jacoco") {
    val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    extensions.configure<JacocoPluginExtension> {
        toolVersion = libs.findVersion("jacoco").get().requiredVersion
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required = true
            xml.outputLocation = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
            html.required = true
            html.outputLocation = layout.buildDirectory.dir("reports/jacoco/test/html")
            csv.required = false
        }
    }
}
