plugins {
    id("conventions.kotlin")
    id("jacoco-integration-tests")
    id("conventions.kotlin-static-analysis")
    id("test-classdiff")
}

viaductClassDiff {
    schemaDiff("schema") {
        actualPackage.set("actuals.api.generated")
        expectedPackage.set("viaduct.api.grts")
        schemaResource("graphql/schema.graphqls")
    }
}

viaductIntegrationCoverage {
    baseProject(":codegen:tenant:tenant-codegen")
}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
    }
    named("test") {
        resources.srcDir("$rootDir/tenant/codegen/src/integrationTest/resources")
    }
}

kotlin {
    sourceSets {
        val test by getting {
            kotlin.srcDir("$rootDir/tenant/codegen/src/integrationTest/kotlin")
        }
    }
}

dependencies {
    // Depend on the codegen module from the codegen layer
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(libs.viaduct.tenant.codegen)
    testImplementation(libs.viaduct.shared.codegen)
    testImplementation(libs.viaduct.shared.graphql)
    testImplementation(libs.viaduct.shared.viaductschema)

    // Test dependencies from original codegen module
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.javassist)
}
