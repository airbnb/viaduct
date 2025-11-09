plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("jacoco-integration-base")
    `maven-publish`
}

viaductPublishing {
    name.set("Codegen")
    description.set("The Viaduct code generator and command-line interface.")
}

dependencies {
    implementation(libs.clikt.jvm)
    implementation(libs.graphql.java)
    implementation(libs.kotlinx.metadata.jvm)
    implementation(libs.viaduct.shared.invariants)
    implementation(libs.viaduct.shared.codegen)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.viaductschema)

    runtimeOnly(libs.viaduct.tenant.api)

    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.javassist)
    testImplementation(testFixtures(libs.viaduct.shared.viaductschema))
}
