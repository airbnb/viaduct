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

dependencies {
    api(libs.viaduct.tenant.api)
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

// Make shadowJar replace the default jar
tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")  // Move default jar out of the way
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
