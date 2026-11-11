import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Test Fixtures")
    description.set("Convenience module for testing Viaduct tenants")
}

dependencies {
    api(testFixtures(libs.viaduct.tenant.api))
}

tasks.named<ShadowJar>("shadowJar") {
    // Package all dependencies (test fixtures from core modules)
    configurations = listOf(project.configurations.runtimeClasspath.get())

    // Exclude third-party classes with rapid API churn that would cause version conflicts
    // (e.g. NoSuchMethodError) when the consumer's versions differ from the bundled ones.
    // Stable APIs (javax, jakarta, micrometer, etc.) are intentionally kept bundled.
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("io/kotest/**")
    exclude("org/opentest4j/**")
    exclude("org/junit/**")
    exclude("META-INF/versions/**/org/junit/**")
    exclude("org/jetbrains/**")
    exclude("org/reactivestreams/**")
    exclude("reactor/**")
    exclude("io/projectreactor/**")
    exclude("_COROUTINE/**")

    // Relocate common dependencies to avoid conflicts
    relocate("com.google.common", "viaduct.shaded.guava")
    relocate("com.google.guava", "viaduct.shaded.guava")
    relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
    relocate("org.slf4j", "viaduct.shaded.slf4j")

    // Engine contract suites only test engine implementations.
    val engineApiTestFixtures = project.configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("engine-api-") && it.name.endsWith("-test-fixtures.jar") }
    val engineContractPackage = "viaduct/engine/runtime/execution/"
    inputs.property("excludedEngineApiTestFixturePackage", engineContractPackage)
    var engineContractSuites: Set<String>? = null
    exclude { element ->
        val suites = engineContractSuites ?: ZipFile(engineApiTestFixtures.singleFile).use { archive ->
            archive.entries().asSequence().map { it.name }
                .filter { it.startsWith(engineContractPackage) && !it.endsWith("/") }.toSet()
        }.also { engineContractSuites = it }
        element.path in suites
    }
}

val checkTestFixturesPublication = tasks.register("checkTestFixturesPublication") {
    group = "verification"
    val fixturesJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    val engineApiTestFixtures = configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("engine-api-") && it.name.endsWith("-test-fixtures.jar") }
    inputs.file(fixturesJar)
    inputs.files(engineApiTestFixtures)
    doLast {
        val engineContractSuites = ZipFile(engineApiTestFixtures.singleFile).use { archive ->
            archive.entries().asSequence().map { it.name }
                .filter { it.startsWith("viaduct/engine/runtime/execution/") && !it.endsWith("/") }.toSet()
        }
        check(engineContractSuites.isNotEmpty()) { "engine-api test fixtures no longer hold the engine contract suites" }
        ZipFile(fixturesJar.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            check("viaduct/engine/runtime/execution/CoroutineContextContract.class" in entries)
            check(entries.none { "org/junit/" in it }) { "Test fixtures fat JAR bundles JUnit" }
            check(entries.none { it in engineContractSuites }) { "Test fixtures fat JAR bundles engine contract suites" }
        }
    }
}

tasks.named("check") { dependsOn(checkTestFixturesPublication) }
