import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("buildroot.versioning")
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("com.google.protobuf") version "0.9.4"
}

// Treat Kotlin compiler warnings as errors, matching the Bazel build's -Werror
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// Not published: this experimental lib is consumed include-only — the starwars demo servers
// includeBuild it from source (see ../starwars/*/settings.gradle.kts).

// Configure detekt to use local config
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    config.setFrom(files("detekt.yml"))
}

dependencies {
    // Viaduct fat jars - api bundles tenant/service API, runtime bundles engine+service+tenant runtime
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

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

    // Network gRPC transport - using shaded version to avoid Netty conflicts with Micronaut
    implementation(libs.grpc.netty.shaded)

    // Jackson for JSON serialization
    implementation(libs.jackson.module)

    // Guice for dependency injection
    implementation(libs.guice)

    api(libs.slf4j.api)

    // Testing dependencies
    testImplementation(libs.junit)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.kotest.assertions.core.jvm)
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
