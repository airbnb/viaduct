import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

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
    }
}

apply(from = rootDir.resolve("gradle/viaduct-maven-central.gradle.kts"))
