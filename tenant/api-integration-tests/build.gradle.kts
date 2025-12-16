plugins {
    id("conventions.kotlin")
    id("jacoco-integration-tests")
    id("test-feature-app")
    id("conventions.kotlin-static-analysis")
}

viaductFeatureApp {}

viaductIntegrationCoverage {
    baseProject(":core:tenant:tenant-api")
}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
    }
    named("test") {
        resources.srcDir("$rootDir/tenant/api/src/integrationTest/resources")
    }
}

kotlin {
    sourceSets {
        val test by getting {
            kotlin.srcDir("$rootDir/tenant/api/src/integrationTest/kotlin")
        }
    }
}

dependencies {
    testImplementation(testFixtures(libs.viaduct.tenant.api))

    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(libs.viaduct.shared.apiannotations)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)
    testImplementation(testFixtures(libs.viaduct.shared.mapping))
    testImplementation(testFixtures(libs.viaduct.engine.api))
}
