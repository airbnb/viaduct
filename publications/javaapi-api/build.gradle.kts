import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Java Tenant API")
    description.set("Fat jar bundle of the Viaduct Java tenant API for use as a GRT compile classpath")
}

dependencies {
    // Always expose as api so composite-build consumers (demo apps) get transitive deps.
    // In the published shadow jar, these are bundled and transitive deps are suppressed below.
    api(libs.viaduct.javaapi.api)
}

tasks.named<ShadowJar>("shadowJar") {
    // Use compileClasspath to get only compile-time API dependencies (not runtime)
    configurations = listOf(project.configurations.compileClasspath.get())

    // Exclude third-party classes with rapid API churn that would cause version conflicts
    // (e.g. NoSuchMethodError) when the consumer's versions differ from the bundled ones.
    // Stable APIs (javax, jakarta, micrometer, etc.) are intentionally kept bundled.
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("io/kotest/**")
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
