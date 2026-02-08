import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    artifactId.set("api")
    name.set("Tenant API")
    description.set("Fat jar bundle of the Viaduct tenant API for easier dependency management")
}

// In composite builds (when demo apps are included), expose transitive deps so consumers don't need explicit declarations.
// For published jars, keep as implementation so they're bundled in the shadow jar without POM conflicts.
val isPublishing = gradle.startParameter.taskNames.any { it.contains("publish") || it.contains("MavenCentral") }
val isCompositeBuild = gradle.includedBuilds.any { it.name.contains("starter") || it.name == "starwars" } && !isPublishing

dependencies {
    api(libs.viaduct.tenant.api)
    if (isCompositeBuild) {
        api(libs.viaduct.service.api)
        api(libs.graphql.java)  // Needed for generated resolver bases
    } else {
        implementation(libs.viaduct.service.api)
        implementation(libs.graphql.java)
    }
}

// Create shaded jar for publishing (fat jar with all dependencies)
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")  // Replace the main jar
    mergeServiceFiles()

    // Use compileClasspath to get only compile-time API dependencies (not runtime)
    configurations = listOf(project.configurations.compileClasspath.get())

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
