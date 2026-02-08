import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    artifactId.set("runtime")
    name.set("Runtime")
    description.set("Convenience module that aggregates all Viaduct runtime modules and their transitive dependencies")
}

// In composite builds (when demo apps are included), expose transitive deps so consumers don't need explicit declarations.
// For published jars, keep as implementation so they're bundled in the shadow jar without POM conflicts.
val isPublishing = gradle.startParameter.taskNames.any { it.contains("publish") || it.contains("MavenCentral") }
val isCompositeBuild = gradle.includedBuilds.any { it.name.contains("starter") || it.name == "starwars" } && !isPublishing

dependencies {
    if (isCompositeBuild) {
        api(libs.viaduct.engine.api)
        api(libs.viaduct.engine.runtime)
        api(libs.viaduct.engine.wiring)
        api(libs.viaduct.service.runtime)
        api(libs.viaduct.service.wiring)
        api(libs.viaduct.tenant.runtime)
        api(libs.viaduct.tenant.wiring)

        // Third-party dependencies used internally by Viaduct
        api(libs.graphql.java)
        api(libs.guice)
        api(libs.javax.inject)
    } else {
        implementation(libs.viaduct.engine.api)
        implementation(libs.viaduct.engine.runtime)
        implementation(libs.viaduct.engine.wiring)
        implementation(libs.viaduct.service.runtime)
        implementation(libs.viaduct.service.wiring)
        implementation(libs.viaduct.tenant.runtime)
        implementation(libs.viaduct.tenant.wiring)

        // Third-party dependencies used internally by Viaduct
        implementation(libs.graphql.java)
        implementation(libs.guice)
        implementation(libs.javax.inject)
    }
}

// Create shaded jar for publishing (fat jar with all dependencies)
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")  // Replace the main jar
    mergeServiceFiles()

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

// Configure apiElements and runtimeElements to use shadow jar
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

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
