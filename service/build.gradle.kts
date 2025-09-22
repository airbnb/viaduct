import viaduct.gradle.internal.repoRoot

plugins {
    id("org.jetbrains.dokka")
    id("conventions.dokka")
}

dependencies {
    dokka(libs.viaduct.service.wiring)
    dokka(libs.viaduct.service.api)
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(repoRoot().dir("docs/static/apis/service"))
        includes.from(layout.projectDirectory.file("module.md"))
    }
    pluginsConfiguration.html {
        customStyleSheets.from(repoRoot().file("docs/kdoc-service-styles.css"))
    }
}
