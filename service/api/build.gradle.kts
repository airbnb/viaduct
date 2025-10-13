import viaduct.gradle.internal.repoRoot

plugins {
    `java-library`
    `maven-publish`
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.dokka")
}

viaductPublishing {
    name.set("Service API")
    description.set("The API/SPI exposed for consumption by Viaduct implementing services.")
}

dependencies {
    implementation(libs.guice)
    implementation(libs.graphql.java)

    implementation(libs.viaduct.engine.api)

    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)

    testFixturesApi(libs.viaduct.engine.api)
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(repoRoot().dir("docs/static/apis/"))
        includes.from(layout.projectDirectory.file("module.md"))
    }
    pluginsConfiguration.html {
        customStyleSheets.from(repoRoot().file("docs/kdoc-service-styles.css"))
    }
}
