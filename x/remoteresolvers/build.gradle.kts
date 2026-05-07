plugins {
    id("buildroot.versioning")
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `maven-publish`
    id("com.google.protobuf") version "0.9.4"
}

// Uses plain maven-publish rather than conventions.viaduct-publishing to avoid
// a Dokka version conflict when this module is consumed as an included build.
// Local-only publication — rrp-server (the only consumer) finds the artifact in ~/.m2/.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // Core Viaduct modules use the "INCLUDED" version-ref placeholder in the catalog;
            // without this mapping the published POM copies that placeholder verbatim and
            // local consumers fail to resolve 'com.airbnb.viaduct.engine:api:INCLUDED'.
            versionMapping {
                allVariants { fromResolutionResult() }
            }
        }
    }
}

// Configure detekt to use local config
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    config.setFrom(files("detekt.yml"))
}

dependencies {
    // Viaduct Engine API - the core interfaces we're implementing
    implementation(libs.viaduct.engine.api)

    // GraphQL Java for schema types
    implementation(libs.graphql.java)

    // Kotlin coroutines for async execution
    implementation(libs.kotlinx.coroutines.core)

    // Protocol Buffers and gRPC
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.core)

    // In-process gRPC transport (used by in-process tests and sample applications)
    implementation(libs.grpc.inprocess)

    // Network gRPC transport - using shaded version to avoid Netty conflicts with Micronaut
    implementation(libs.grpc.netty.shaded)

    // Jackson for JSON serialization
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module)

    // Guice for dependency injection
    implementation(libs.guice)

    api(libs.slf4j.api)
    // Composite builds use isolated classloaders, so each module ships its own SLF4J provider.
    runtimeOnly(libs.slf4j.simple)

    // Testing dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test fixtures from Viaduct engine for mocks
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.engine.runtime))

    // GraphQL Java for test fixtures
    testImplementation(libs.graphql.java)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.66.0"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                create("kotlin")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("src/main/proto")
        }
    }
}

// Handle duplicate proto files from generated sources
tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
