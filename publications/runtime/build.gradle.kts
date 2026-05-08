import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    name.set("Runtime")
    description.set("Convenience module that aggregates all Viaduct runtime modules and their transitive dependencies")
}

dependencies {
    // Always expose as api so composite-build consumers (demo apps) get transitive deps.
    // In the published shadow jar, these are bundled and transitive deps are suppressed below.
    api(libs.viaduct.engine.api)
    api(libs.viaduct.engine.runtime)
    api(libs.viaduct.engine.wiring)
    api(libs.viaduct.service.runtime)
    api(libs.viaduct.service.wiring)
    api(libs.viaduct.tenant.runtime)
    api(libs.viaduct.tenant.wiring)

    // Third-party dependencies used internally by Viaduct
    api(libs.graphql.java)
    api(libs.guice)
    api(libs.javax.inject)
}

// Create shaded jar for publishing (fat jar with all dependencies).
// Excludes classes already provided by the `api` publication so the two jars are
// complementary — consumers declare both without duplicate-class conflicts.
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")  // Replace the main jar
    mergeServiceFiles()

    // Exclude classes already bundled in the `api` publication. The api jar packages
    // tenant.api, service.api, shared.apiannotations, shared.errors, and graphql-java
    // (plus relocated Guava pulled transitively by graphql-java). Keeping these out of
    // the runtime jar prevents duplicate-class conflicts for consumers that declare both.
    exclude("viaduct/api/**")
    exclude("viaduct/service/api/**")
    exclude("viaduct/apiannotations/**")
    exclude("viaduct/errors/**")
    exclude("graphql/**")

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

// Make the default jar task produce the shadow jar output
tasks.named<Jar>("jar") {
    enabled = false
}

// Configure apiElements and runtimeElements to use shadow jar.
// The shadow jar is self-contained for all bundled Viaduct classes, so we suppress
// transitive runtime dependencies to prevent old bundled versions (e.g. coroutines)
// from leaking onto consumers' classpaths alongside the shadow jar.
configurations {
    named("apiElements") {
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
    named("runtimeElements") {
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
}

// Suppress runtimeElements from Gradle module metadata so consumers don't resolve
// transitive deps that are already bundled in the shadow jar.
plugins.withId("org.jetbrains.kotlin.jvm") {
    val javaComponent = components["java"] as AdhocComponentWithVariants
    javaComponent.withVariantsFromConfiguration(configurations.runtimeElements.get()) {
        skip()
    }
}

// Strip transitive dependencies from POM for Maven consumers.
afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        pom.withXml {
            val deps = asNode().get("dependencies") as groovy.util.NodeList
            deps.forEach { (it as groovy.util.Node).parent().remove(it) }
        }
    }
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
