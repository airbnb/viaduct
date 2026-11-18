import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Java Runtime")
    description.set("Fat jar bundle of the Viaduct Java-to-Kotlin bridge runtime")
}

dependencies {
    // Always expose as api so composite-build consumers (demo apps) get transitive deps.
    // In the published shadow jar, these are bundled, and its POM depends only on the fat jars in fatJarLayers.
    api(libs.viaduct.javaapi.runtime)
    fatJarLayers(project(":api"))
    fatJarLayers(project(":javaapi-api"))
    fatJarLayers(project(":runtime"))
}

tasks.named<ShadowJar>("shadowJar") {
    // Exclude third-party classes with rapid API churn that would cause version conflicts
    // (e.g. NoSuchMethodError) when the consumer's versions differ from the bundled ones.
    // Stable APIs (javax, jakarta, micrometer, etc.) are intentionally kept bundled.
    exclude("kotlin/**")
    exclude("META-INF/versions/*/kotlin/**")
    exclude("kotlinx/**")
    exclude("io/kotest/**")
    exclude("org/jetbrains/**")
    exclude("reactor/**")
    exclude("io/projectreactor/**")
    exclude("_COROUTINE/**")
}
