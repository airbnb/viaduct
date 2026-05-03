package conventions

import viaduct.gradle.internal.repoRoot

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
}

checkstyle {
    toolVersion = "10.12.4"
    configFile = repoRoot().file("config/checkstyle/checkstyle.xml").get().asFile
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
