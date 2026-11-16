plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    // Java annotation types (@Resolver, @ResolverFor, @NodeResolverFor, @Variable, @Variables)
    implementation(project(":javaapi:api"))

    // Shared descriptor model + JSON codec — single source of truth for the registry JSON shape.
    implementation(libs.viaduct.tenant.codegen)

    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.clikt.jvm)
    testImplementation(libs.viaduct.service.api)
}
