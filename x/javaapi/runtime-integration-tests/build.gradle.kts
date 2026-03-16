plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("test-java-feature-app")
}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
    }
}

// Include JavaFeatureAppTestBase and fixtures from the runtime module's test sources
kotlin {
    sourceSets {
        val test by getting {
            kotlin.srcDir("$rootDir/x/javaapi/runtime/src/test/kotlin")
        }
    }
}

dependencies {
    // Java codegen classpath for process-isolated schema/tenant generation
    viaductCodegenClasspath(libs.viaduct.javaapi.codegen)

    // Java API runtime
    testImplementation(libs.viaduct.javaapi.runtime)
    testImplementation(libs.viaduct.javaapi.api)

    // Engine and service dependencies for test infrastructure
    testImplementation(libs.viaduct.engine.api)
    testImplementation(libs.viaduct.engine.runtime)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(libs.viaduct.service.api)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(libs.viaduct.service.wiring)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.service.api))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))

    // GraphQL
    testImplementation(libs.graphql.java)

    // Shared ClassGraph scanner utility (needed by JavaFeatureAppTestBase)
    testImplementation(libs.viaduct.shared.utils)

    // Coroutines (needed by test infrastructure)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.jdk8)
    testImplementation(libs.kotlinx.coroutines.test)

    // Shared GraphQL utils
    testImplementation(libs.viaduct.shared.graphql)

    // javax.inject
    testImplementation(libs.javax.inject)

    // Testing
    testImplementation(libs.assertj.core)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.slf4j.api)
}
