import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

plugins {
    id("conventions.viaduct-fat-jar")
}

val checkRuntime2Publication = tasks.register("checkRuntime2Publication") {
    group = "verification"
    notCompatibleWithConfigurationCache("Runtime2 publication verification retains runtime2's configuration-cache opt-out.")
    val runtimeJar = tasks.named<ShadowJar>("shadowJar")
    val supportInventory = file("../../core/engine/runtime2/build/reports/support-output-inventory.txt")
    dependsOn(runtimeJar, gradle.includedBuild("core").task(":engine:runtime2:checkProductionBoundary"))
    inputs.file(runtimeJar.flatMap { it.archiveFile })
    inputs.file(supportInventory)
    doLast {
        val excluded = supportInventory.readLines().toSet()
        ZipFile(runtimeJar.get().archiveFile.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toSet()
            check("viaduct/engine/runtime2/execution/QPlanExecutionStrategy.class" in entries)
            check(entries.intersect(excluded).isEmpty()) { "Runtime fat JAR contains runtime2 support output" }
        }
    }
}

tasks.named("check") { dependsOn(checkRuntime2Publication) }

viaductPublishing {
    name.set("Runtime")
    description.set("Convenience module that aggregates all Viaduct runtime modules and their transitive dependencies")
}

dependencies {
    // Always expose as api so composite-build consumers (demo apps) get transitive deps.
    // In the published shadow jar, these are bundled and transitive deps are suppressed below.
    api(libs.viaduct.engine.api)
    api(libs.viaduct.engine.runtime)
    api(libs.viaduct.engine.runtime2)
    api(libs.viaduct.engine.wiring)
    api(libs.viaduct.service.runtime)
    api(libs.viaduct.service.wiring)
    api(libs.viaduct.tenant.runtime)
    api(libs.viaduct.tenant.wiring)

    // Third-party dependencies used internally by Viaduct
    api(libs.graphql.java)
    api(libs.guice)
    api(libs.javax.inject)
    compileOnly(libs.slf4j.api)
}

// Excludes classes already provided by the `api` publication so the two jars are
// complementary — consumers declare both without duplicate-class conflicts.
tasks.named<ShadowJar>("shadowJar") {
    // Exclude Viaduct API classes already bundled in the `api` publication.
    // graphql-java core is intentionally kept bundled (duplicated with the api jar) so
    // that graphql-java-extended-scalars (graphql/scalars/**) remains available at runtime.
    exclude("viaduct/api/**")
    exclude("viaduct/service/api/**")
    exclude("viaduct/apiannotations/**")
    exclude("viaduct/errors/**")

    // Exclude third-party classes with rapid API churn that would cause version conflicts
    // (e.g. NoSuchMethodError) when the consumer's versions differ from the bundled ones.
    // Stable APIs (javax, jakarta, micrometer, etc.) are intentionally kept bundled.
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("io/kotest/**")
    exclude("org/junit/**")
    exclude("META-INF/versions/**/org/junit/**")
    exclude("org/jetbrains/**")
    exclude("reactor/**")
    exclude("io/projectreactor/**")
    exclude("_COROUTINE/**")

    // Relocate common dependencies to avoid conflicts
    relocate("com.google.common", "viaduct.shaded.guava")
    relocate("com.google.guava", "viaduct.shaded.guava")
    relocate("com.fasterxml.jackson", "viaduct.shaded.jackson")
    relocate("org.slf4j", "viaduct.shaded.slf4j")
}
