package conventions

import org.gradle.api.plugins.quality.Checkstyle
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

// checkstyle.xml's default severity is `warning`, and `isIgnoreFailures = false` only fails the
// build on `error`-severity violations. Cap warnings at zero so warning-severity findings fail too.
tasks.withType<Checkstyle>().configureEach {
    maxWarnings = 0
}
