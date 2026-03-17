plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(libs.graphql.java)
    api(libs.viaduct.shared.apiannotations)

    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
}
