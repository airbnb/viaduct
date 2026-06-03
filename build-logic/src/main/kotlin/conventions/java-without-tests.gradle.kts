package conventions

import viaduct.gradle.internal.repoRoot

plugins {
    idea
    java
    checkstyle
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
