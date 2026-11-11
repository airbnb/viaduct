plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    compileOnly(project(":common"))
    implementation(libs.viaduct.federation.validation)

    testImplementation(project(":conventions"))
    testRuntimeOnly(project(":federation"))
}

viaductPublishing {
    name.set("Foundation Federation Validation SPI")
    description.set("Integrated schema validation provider for GraphQL Foundation federation.")
}
