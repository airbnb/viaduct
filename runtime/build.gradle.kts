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

dependencies {
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

// Create shaded jar for publishing (fat jar with all dependencies)
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")  // Replace the main jar
    mergeServiceFiles()

    // Relocate common dependencies to avoid conflicts
    relocate("com.google.common", "viaduct.shaded.guava")
    relocate("com.google.guava", "viaduct.shaded.guava")
    relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
    relocate("org.slf4j", "viaduct.shaded.slf4j")
    relocate("com.google.inject", "viaduct.shaded.guice")
    relocate("graphql", "viaduct.shaded.graphql")
    relocate("javax.inject", "viaduct.shaded.javax.inject")
}

// Make shadowJar replace the default jar
tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")  // Move default jar out of the way
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
