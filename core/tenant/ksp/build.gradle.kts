plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    name.set("Tenant KSP")
    description.set("KSP symbol traversal utilities for Viaduct tenant annotation processing")
}

dependencies {
    compileOnly(libs.ksp.symbol.processing.api)

    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.tenant.validation)
    implementation(libs.graphql.java)
    implementation(libs.jackson.module)
    implementation(libs.jackson.annotations)

    testImplementation(libs.ksp.symbol.processing.api)
    testImplementation(libs.graphql.java)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
