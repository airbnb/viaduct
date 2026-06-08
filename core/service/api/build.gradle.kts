import viaduct.gradle.internal.repoRoot

plugins {
    `java-library`
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.dokka")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestFixturesKotlin") {
    compilerOptions.moduleName.set("service-api_testFixtures")
}

dependencies {
    /** External dependencies **/
    implementation(libs.guice)
    implementation(libs.graphql.java)

    /** Viaduct dependencies **/
    api(libs.viaduct.shared.apiannotations)
    implementation(libs.viaduct.shared.graphql)

    /** Test fixtures dependencies **/
    testFixturesImplementation(libs.viaduct.engine.api)

    /** Test dependencies - External **/
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(repoRoot().dir("docs/site/apis/service-api"))
        includes.from(layout.projectDirectory.file("module.md"))
    }
    pluginsConfiguration.html {
        customStyleSheets.from(repoRoot().file("docs/kdoc-service-styles.css"))
    }
}
