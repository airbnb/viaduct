import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    name.set("Test Fixtures")
    description.set("Convenience module for testing Viaduct tenants")
}

dependencies {
    api(testFixtures(libs.viaduct.tenant.api))
}

// Create shaded jar for publishing (fat jar with all test fixtures)
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")  // Replace the main jar
    mergeServiceFiles()

    // Package all dependencies (test fixtures from core modules)
    configurations = listOf(project.configurations.runtimeClasspath.get())

    // Exclude third-party classes with rapid API churn that would cause version conflicts
    // (e.g. NoSuchMethodError) when the consumer's versions differ from the bundled ones.
    // Stable APIs (javax, jakarta, micrometer, etc.) are intentionally kept bundled.
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("io/kotest/**")
    exclude("org/jetbrains/**")
    exclude("org/reactivestreams/**")
    exclude("reactor/**")
    exclude("io/projectreactor/**")
    exclude("_COROUTINE/**")

    // Relocate common dependencies to avoid conflicts
    relocate("com.google.common", "viaduct.shaded.guava")
    relocate("com.google.guava", "viaduct.shaded.guava")
    relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
    relocate("org.slf4j", "viaduct.shaded.slf4j")
}

// Make the default jar task produce the shadow jar output
tasks.named<Jar>("jar") {
    enabled = false
}

// Configure apiElements and runtimeElements to use shadow jar.
// The shadow jar is self-contained for all bundled Viaduct classes, so we suppress
// transitive runtime dependencies to prevent old bundled versions (e.g. coroutines)
// from leaking onto consumers' classpaths alongside the shadow jar.
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

// Suppress Gradle module metadata — the fat jar is self-contained and the standard
// variants would reference internal viaduct coordinates that aren't published individually.
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

// Strip all transitive dependencies from the published POM.
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
