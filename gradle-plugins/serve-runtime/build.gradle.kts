plugins {
    id("conventions.kotlin")
    `maven-publish`
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // Publish thin jar with dependencies (for both composite builds and Maven Central)
            // Gradle automatically substitutes this with the local project in composite mode
            from(components["java"])

            // Resolve actual versions for dependencies that use "INCLUDED" placeholder
            // This ensures the POM contains real version numbers when published
            versionMapping {
                usage("java-api") { fromResolutionOf("runtimeClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }

            artifact(tasks["sourcesJar"])
            artifact(emptyJavadocJar.get())

            groupId = "com.airbnb.viaduct"
            artifactId = "serve"

            pom {
                name.set("Viaduct Serve")
                description.set("Development server runtime for Viaduct GraphQL applications with GraphiQL IDE")
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

dependencies {
    // Viaduct dependencies
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.service.wiring)
    implementation(libs.viaduct.tenant.api)

    // GraphQL
    implementation(libs.graphql.java)

    // Ktor server
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-server-cors:3.0.3")
    implementation("io.ktor:ktor-server-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-jackson:3.0.3")

    // Classpath scanning for finding @ViaductApplication
    implementation(libs.classgraph)

    // Logging
    implementation(libs.slf4j.api)
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core.jvm)

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}

