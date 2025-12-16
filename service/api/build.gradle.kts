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
    /** External dependencies **/
    implementation(libs.guice)
    implementation(libs.graphql.java)

    /** Viaduct dependencies **/
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.apiannotations)

    /** Test dependencies - External **/
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)
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
