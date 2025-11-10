package conventions

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("com.github.johnrengelman.shadow")
    id("conventions.viaduct-publishing")
    java
}

val sourceSets = the<SourceSetContainer>()

// Configure the main shadow jar (for runtime dependencies)
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")

    // Merge service files
    mergeServiceFiles()

    // Relocate common dependencies to avoid conflicts
    relocate("com.google.common", "viaduct.shaded.guava")
    relocate("com.google.guava", "viaduct.shaded.guava")
    relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
    relocate("org.slf4j", "viaduct.shaded.slf4j")
    relocate("javax.inject", "viaduct.shaded.javax.inject")
    relocate("com.google.inject", "viaduct.shaded.guice")

    // Minimize the jar by removing unused classes
    // Commented out for now as it can be aggressive
    // minimize()
}

// Create an API shadow jar (compile-time dependencies only)
// This includes only compile-time dependencies. Any transitive dependencies
// that are on the compile classpath will be automatically included and shaded
// by the relocation rules below.
val shadowApiJar by tasks.registering(ShadowJar::class) {
    archiveClassifier.set("api-all")
    group = "shadow"
    description = "Create a fat JAR with API (compile-time) dependencies"

    // Use the main source set
    from(sourceSets.main.get().output)

    // Include only compile classpath - this is correct for compile-time-only API jars
    configurations = listOf(
        project.configurations.compileClasspath.get()
    )

    mergeServiceFiles()

    // Relocation rules - these will automatically shade ANY classes in these packages
    // that are included in the jar, whether they're direct or transitive dependencies
    relocate("com.google.common", "viaduct.shaded.guava")
    relocate("com.google.guava", "viaduct.shaded.guava")
    relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
    relocate("org.slf4j", "viaduct.shaded.slf4j")
    relocate("javax.inject", "viaduct.shaded.javax.inject")
    relocate("com.google.inject", "viaduct.shaded.guice")
}

// Create test fixtures shadow jar if java-test-fixtures is enabled
plugins.withId("java-test-fixtures") {
    val shadowTestFixturesJar by tasks.registering(ShadowJar::class) {
        archiveClassifier.set("test-fixtures-all")
        group = "shadow"
        description = "Create a fat JAR with test fixtures and their dependencies"

        // Use test fixtures source set
        val testFixturesSourceSet = sourceSets.getByName("testFixtures")
        from(testFixturesSourceSet.output)

        // Include test fixtures dependencies
        configurations = listOf(
            project.configurations.getByName("testFixturesCompileClasspath")
        )

        mergeServiceFiles()

        // Same relocation rules
        relocate("com.google.common", "viaduct.shaded.guava")
        relocate("com.google.guava", "viaduct.shaded.guava")
        relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
        relocate("org.slf4j", "viaduct.shaded.slf4j")
        relocate("javax.inject", "viaduct.shaded.javax.inject")
        relocate("com.google.inject", "viaduct.shaded.guice")
    }

}

// Make shadow jars part of the build
tasks.named("assemble") {
    dependsOn("shadowJar", shadowApiJar)
}

plugins.withId("java-test-fixtures") {
    tasks.named("assemble") {
        dependsOn("shadowTestFixturesJar")
    }
}

// Configure publishing to include shadow jars as additional artifacts
// These are added after the main publication is configured by viaduct-publishing
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn("shadowJar", shadowApiJar)
}

plugins.withId("java-test-fixtures") {
    tasks.withType<AbstractPublishToMaven>().configureEach {
        dependsOn("shadowTestFixturesJar")
    }
}

// Note: The vanniktech maven-publish plugin automatically includes the shadowJar
// in the publication, so we don't need to manually add it. We only need to ensure
// the shadow tasks are created and configured, which is done above.

// However, we DO need to manually add the API and test-fixtures shadow jars
// since those are custom tasks that the plugin doesn't know about
afterEvaluate {
    publishing {
        publications {
            withType<MavenPublication>().configureEach {
                // Add API shadow jar (the main shadowJar is added automatically by the plugin)
                artifact(shadowApiJar.get())

                // Add test fixtures shadow jar if it exists
                tasks.findByName("shadowTestFixturesJar")?.let { task ->
                    artifact(task)
                }
            }
        }
    }
}
