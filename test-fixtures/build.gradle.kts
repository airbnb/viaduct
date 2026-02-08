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
