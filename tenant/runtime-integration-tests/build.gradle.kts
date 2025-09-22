plugins {
    id("conventions.kotlin")
    id("test-feature-app")
    id("conventions.kotlin-static-analysis")
}

viaductFeatureApp {}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
    }
    named("test") {
        resources.srcDir("$rootDir/tenant/runtime/src/integrationTest/resources")
    }
}

kotlin {
    sourceSets {
        val test by getting {
            kotlin.srcDir("$rootDir/tenant/runtime/src/integrationTest/kotlin")
        }
    }
}

dependencies {
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.service.api))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(libs.viaduct.engine.runtime)
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(project(":tenant:testapps:fixtures"))
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.jackson.core)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.jdk8)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)
    testImplementation(libs.micrometer.core)
}
