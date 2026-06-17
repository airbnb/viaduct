plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    name.set("Java API Registry Annotation Processor")
    description.set(
        "javac annotation processor that extracts the file-based execution registry descriptors " +
            "from Java tenant resolvers (the Java twin of the Kotlin KSP RegistryExtractorProcessor).",
    )
}

dependencies {
    // Java annotation types (@Resolver, @ResolverFor, @NodeResolverFor, @Variable, @Variables)
    implementation(project(":x:javaapi:api"))

    // Shared descriptor model + JSON codec — single source of truth for the registry JSON shape.
    implementation(libs.viaduct.tenant.codegen)

    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module)
}
