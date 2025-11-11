import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.github.johnrengelman.shadow")
    `java-test-fixtures`
    `maven-publish`
    signing
}

dependencies {
    // For API jar: use api() to get compile-time dependencies with transitives
    api(libs.viaduct.engine.api)
    api(libs.viaduct.service.api)
    api(libs.viaduct.tenant.api)

    // For runtime jar: all runtime and wiring dependencies
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.engine.wiring)
    implementation(libs.viaduct.service.runtime)
    implementation(libs.viaduct.service.wiring)
    implementation(libs.viaduct.tenant.runtime)
    implementation(libs.viaduct.tenant.wiring)

    // For test fixtures jar: include all test fixtures
    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(testFixtures(libs.viaduct.engine.runtime))
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.runtime))
}

// Helper function to configure common shadow jar settings
fun ShadowJar.configureCommonSettings() {
    mergeServiceFiles()

    // Relocate common dependencies to avoid conflicts
    // NOTE: We do NOT relocate javax.inject or Guice as these are part of the API contract
    // that users depend on. Applications use @Inject annotations and Guice modules directly.
    relocate("com.google.common", "viaduct.shaded.guava")
    relocate("com.google.guava", "viaduct.shaded.guava")
    relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
    relocate("org.slf4j", "viaduct.shaded.slf4j")
}

// 1. API shaded jar (compile-time dependencies only)
val shadowApiJar by tasks.registering(ShadowJar::class) {
    archiveBaseName.set("viaduct-api-shaded")
    archiveClassifier.set("")
    group = "shadow"
    description = "Shaded JAR with API (compile-time) dependencies"

    // Use compileClasspath to get only compile-time dependencies
    configurations = listOf(project.configurations.compileClasspath.get())

    configureCommonSettings()
}

// 2. Runtime shaded jar (all runtime dependencies)
val shadowRuntimeJar by tasks.registering(ShadowJar::class) {
    archiveBaseName.set("viaduct-runtime-shaded")
    archiveClassifier.set("")
    group = "shadow"
    description = "Shaded JAR with runtime dependencies"

    // Use runtimeClasspath to get all runtime dependencies
    configurations = listOf(project.configurations.runtimeClasspath.get())

    configureCommonSettings()
}

// 3. Test fixtures shaded jar
val shadowTestFixturesJar by tasks.registering(ShadowJar::class) {
    archiveBaseName.set("viaduct-test-fixtures-shaded")
    archiveClassifier.set("")
    group = "shadow"
    description = "Shaded JAR with test fixtures"

    // Use test fixtures runtime classpath to get all test fixture dependencies
    configurations = listOf(project.configurations.getByName("testFixturesRuntimeClasspath"))

    configureCommonSettings()
}

// Make shadow jars part of the build
tasks.named("assemble") {
    dependsOn(shadowApiJar, shadowRuntimeJar, shadowTestFixturesJar)
}

// Disable the default jar task since we're only producing shaded jars
tasks.named("jar") {
    enabled = false
}

// Configure publishing to include all three shaded jars
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.airbnb.viaduct"
            artifactId = "viaduct-shaded"
            version = project.version.toString()

            // Add all three shaded jars as artifacts
            artifact(shadowApiJar.get()) {
                classifier = "api"
            }

            artifact(shadowRuntimeJar.get()) {
                classifier = "runtime"
            }

            artifact(shadowTestFixturesJar.get()) {
                classifier = "test-fixtures"
            }

            pom {
                name.set("Viaduct :: Shaded")
                description.set("Shaded fat jars containing all Viaduct dependencies with relocated packages")
                url.set("https://airbnb.io/viaduct/")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("airbnb")
                        name.set("Airbnb, Inc.")
                        email.set("viaduct-maintainers@airbnb.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/airbnb/viaduct.git")
                    developerConnection.set("scm:git:ssh://github.com/airbnb/viaduct.git")
                    url.set("https://github.com/airbnb/viaduct")
                }
            }
        }
    }
}

signing {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    setRequired {
        gradle.taskGraph.allTasks.any { it is PublishToMavenRepository }
    }
    sign(publishing.publications["maven"])
}
