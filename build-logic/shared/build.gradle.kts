import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.SigningExtension

plugins {
    kotlin("jvm")
    alias(libs.plugins.gradle.maven.publish)
    signing
}

group = "com.airbnb.viaduct"

fun findVersionFile(start: File): File {
    var d: File? = start
    while (d != null) {
        val f = File(d, "VERSION")
        if (f.exists()) return f
        d = d.parentFile
    }
    error("VERSION file not found starting from: $start")
}

version = findVersionFile(rootDir).readText().trim()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

mavenPublishing {
    val isRelease = providers.environmentVariable("RELEASE").orElse("false").get().toBoolean()
    publishToMavenCentral(automaticRelease = true)
    if (isRelease) signAllPublications()
    configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = false))
    coordinates("com.airbnb.viaduct", "build-shared", version.toString())
    pom {
        name.set("Viaduct :: Build Shared")
        description.set("Shared build utilities for Viaduct Gradle plugins")
        url.set("https://viaduct.airbnb.tech/")
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

val signingKeyId = findProperty("signingKeyId") as String?
val signingKey = findProperty("signingKey") as String?
val signingPassword = findProperty("signingPassword") as String?
val signingExt = project.extensions.getByType(SigningExtension::class.java)
signingExt.useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
signingExt.setRequired { gradle.taskGraph.allTasks.any { it is PublishToMavenRepository } }
signingExt.sign(project.extensions.getByType(PublishingExtension::class.java).publications)

// For snapshot publications — see conventions/viaduct-publishing.gradle.kts for explanation.
run {
    val isRelease = providers.environmentVariable("RELEASE").orElse("false").get().toBoolean()
    if (!isRelease) {
        publishing {
            repositories {
                maven {
                    name = "snapshots"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                    credentials {
                        username = providers.gradleProperty("mavenCentralUsername").orNull
                        password = providers.gradleProperty("mavenCentralPassword").orNull
                    }
                }
            }
        }
    }
}
