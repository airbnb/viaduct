package conventions

plugins {
    idea
    java
    checkstyle
    id("com.github.spotbugs")
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    // Note: The java-stub.md document specifies source/target compatibility 1.8,
    // but this causes JVM-target mismatch when mixed with Kotlin (which uses target 17).
    // Using target 17 for now - can be revisited if pure-Java projects need 1.8.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

checkstyle {
    toolVersion = "10.12.4"
    // Find config file relative to actual OSS root, not the included build
    val ossRoot = if (rootProject.name == "core") {
        rootProject.projectDir.parentFile.parentFile
    } else {
        rootProject.projectDir
    }
    configFile = file("$ossRoot/config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

spotbugs {
    toolVersion = "4.8.1"
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.LOW
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports {
        create("html") {
            required.set(true)
        }
        create("xml") {
            required.set(false)
        }
    }
}
