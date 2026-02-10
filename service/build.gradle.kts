import viaduct.gradle.internal.repoRoot

plugins {
    id("conventions.dokka")
}

dependencies {
    dokka(libs.viaduct.service.wiring)
    dokka(libs.viaduct.service.api)
    // Required for aggregating multiple Dokka modules into a single publication
    dokkaHtmlPublicationPlugin(libs.dokka.all.modules.page.plugin)
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(repoRoot().dir("docs/site/apis/service"))
        includes.from(layout.projectDirectory.file("module.md"))
    }
    pluginsConfiguration.html {
        customStyleSheets.from(repoRoot().file("docs/kdoc-service-styles.css"))
    }
}
