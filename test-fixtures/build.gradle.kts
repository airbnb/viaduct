import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    artifactId.set("test-fixtures")
    name.set("Test Fixtures")
    description.set("Convenience module for testing Viaduct tenants")
}

dependencies {
    api(testFixtures(libs.viaduct.tenant.api))
    implementation(testFixtures(libs.viaduct.tenant.runtime))
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

// Strip all transitive dependencies from the published POM and Gradle module metadata.
// The shadow jar bundles all Viaduct classes directly, so declaring transitive deps
// would cause consumers to resolve old versions of bundled libs (e.g. coroutines 1.8.0
// from tenant-runtime) alongside the shadow jar, producing NoSuchMethodErrors at runtime.
// Third-party deps excluded from the jar are resolved by consumers at their own versions.
plugins.withId("org.jetbrains.kotlin.jvm") {
    val javaComponent = components["java"] as AdhocComponentWithVariants
    // Suppress runtimeElements variant from Gradle module metadata so consumers don't
    // resolve the transitive deps that are already bundled in the shadow jar.
    javaComponent.withVariantsFromConfiguration(configurations.runtimeElements.get()) {
        skip()
    }
}
afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        // The shadow jar bundles all Viaduct classes; transitive deps are intentionally
        // stripped from the POM. The capability-based dep on tenant-api test fixtures
        // cannot be represented in Maven POM but is irrelevant since the POM has no deps.
        suppressPomMetadataWarningsFor("apiElements")
        suppressPomMetadataWarningsFor("runtimeElements")
        pom.withXml {
            val deps = asNode().get("dependencies") as groovy.util.NodeList
            deps.forEach { (it as groovy.util.Node).parent().remove(it) }
        }
    }
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
