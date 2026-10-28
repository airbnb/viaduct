import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-library`
    id("me.champeau.jmh") version "0.7.3"
}

pluginManager.withPlugin("java-test-fixtures") {
    throw GradleException("runtime2 test fixtures must stay unpublished, so do not apply java-test-fixtures")
}

val testFixtures by sourceSets.creating {
    resources.exclude("**/BUILD.bazel")
}

kotlin {
    target.compilations {
        named("testFixtures") { associateWith(getByName("main")) }
    }
}

configurations[testFixtures.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[testFixtures.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
listOf("test", "jmh").forEach { sourceSetName ->
    configurations["${sourceSetName}Implementation"].extendsFrom(configurations[testFixtures.implementationConfigurationName])
    configurations["${sourceSetName}RuntimeOnly"].extendsFrom(configurations[testFixtures.runtimeOnlyConfigurationName])
    dependencies.add("${sourceSetName}Implementation", testFixtures.output)
}
dependencies.add(testFixtures.implementationConfigurationName, sourceSets.main.get().output)

dependencies {
    api(libs.viaduct.engine.api)
    api(libs.viaduct.shared.viaductschema)
    api(libs.viaduct.shared.graphql)
    api(libs.graphql.java)
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)

    add("testFixturesImplementation", libs.guice)
    add("testFixturesImplementation", libs.javax.inject)
    add("testFixturesImplementation", libs.kotest.property.jvm)
    add("testFixturesImplementation", libs.kotest.assertions.core.jvm)
    add("testFixturesImplementation", libs.jackson.module)
    add("testFixturesImplementation", kotlin("test"))
    add(
        "testFixturesImplementation",
        (testFixtures(libs.viaduct.engine.api.get()) as ModuleDependency).apply {
            // Benchmark executors use fixture wiring, not its JUnit assertions.
            exclude(mapOf("group" to "org.junit.jupiter"))
            exclude(mapOf("group" to "org.junit.platform"))
        },
    )

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(libs.viaduct.service.api)
    testImplementation(libs.viaduct.tenant.shared)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(libs.viaduct.shared.dataloader)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.engine.runtime))
    testImplementation(testFixtures(libs.viaduct.shared.arbitrary))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.service.api))
}

jmh {
    includeTests.set(false)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.moduleName.set("engine-runtime2_$name")
}
// The Viaduct conventions withhold these opt-ins from testFixtures, which here hold unpublished reference code.
tasks.named<KotlinCompile>("compileTestFixturesKotlin") {
    compilerOptions.optIn.addAll(
        "viaduct.apiannotations.ExperimentalApi",
        "viaduct.apiannotations.InternalApi",
        "viaduct.apiannotations.VisibleForTest",
    )
}
tasks.configureEach {
    notCompatibleWithConfigurationCache("Runtime2 retains qplan's configuration-cache opt-out.")
}

tasks.named<JacocoReport>("jacocoTestReport") {
    sourceDirectories.from(testFixtures.allSource.srcDirs)
    classDirectories.from(testFixtures.output.classesDirs)
}

apply(from = "development.gradle.kts")
apply(from = "documentation.gradle.kts")
apply(from = "verification.gradle.kts")
