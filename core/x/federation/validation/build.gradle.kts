plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    compileOnly(libs.viaduct.shared.viaductschema)
    implementation(libs.graphql.java)

    testImplementation(libs.viaduct.shared.viaductschema)
}

viaductPublishing {
    name.set("Foundation Federation Validation")
    description.set("Build-time field-selection validation for GraphQL Foundation federation.")
}
