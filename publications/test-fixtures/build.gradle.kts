import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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
}
