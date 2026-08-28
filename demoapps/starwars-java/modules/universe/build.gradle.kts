plugins {
    `java-library`
    alias(libs.plugins.viaduct.module.java)
}

dependencies {
    api(libs.viaduct.javaapi.api)

    implementation(project(":common"))
    implementation(libs.micronaut.inject)
    annotationProcessor(libs.micronaut.inject.java)
}
