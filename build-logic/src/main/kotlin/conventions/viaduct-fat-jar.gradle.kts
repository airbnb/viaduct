package conventions

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

// Replace the default jar with the shadow jar in all fat-jar publications.
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()

    // Drop upstream JAR signing artifacts — relocating bundled classes invalidates them
    // and leaving them in place causes "SecurityException: Invalid signature" at runtime.
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/INDEX.LIST")

    // DO NOT exclude META-INF/maven/** — those per-artifact pom.properties files are how
    // external CVE scanners (Trivy, Grype, GitHub dep graph) identify shaded transitive
    // deps inside this fat JAR. Removing them blinds scanners to relocated CVE-bearing
    // libraries and silently invalidates the fine-grained vs fat-JAR parity check in CI.
}

tasks.named<Jar>("jar") {
    enabled = false
}

// Wire the shadow jar into the Gradle variant system so dependency resolution delivers the fat jar
// to consumers instead of the (disabled) thin jar. The fat jar is self-contained for all bundled
// classes, so transitive runtime dependencies are suppressed to prevent old bundled versions (e.g.
// coroutines) from leaking onto consumers' classpaths alongside the shadow jar.
configurations {
    named("apiElements") {
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
    named("runtimeElements") {
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
}

// Suppress Gradle module metadata — the fat jar is self-contained and the standard variants would
// reference internal viaduct coordinates that aren't published individually.
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

// Strip transitive dependencies from the published POM for Maven consumers — everything is bundled.
afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        pom.withXml {
            val deps = asNode().get("dependencies") as groovy.util.NodeList
            deps.forEach { (it as groovy.util.Node).parent().remove(it) }
        }
    }
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}

// Composite-mode collision guard. In an included build, facade consumers receive the transitive
// core jars alongside this project's shadow jar — the POM-dependency stripping (above) that keeps
// published consumers seeing only the self-contained fat jar does not apply to composite source
// substitution. If two core modules resolve to the same archive filename (e.g. engine/service/
// tenant all producing `runtime-<version>.jar`), a consumer that flattens this classpath into one
// directory (distTar/distZip/buildLayers) collides. Model that flatten directly and fail fast: Sync
// every runtimeClasspath artifact into a temp dir with DuplicatesStrategy.FAIL.
val checkRuntimeClasspathArtifactNamesAreUnique by tasks.registering(Sync::class) {
    group = "verification"
    description = "Fails if runtimeClasspath contains duplicate artifact filenames."

    from(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("tmp/checkRuntimeClasspathArtifactNamesAreUnique/lib"))

    duplicatesStrategy = DuplicatesStrategy.FAIL
    includeEmptyDirs = false
}

tasks.named("check") {
    dependsOn(checkRuntimeClasspathArtifactNamesAreUnique)
}
