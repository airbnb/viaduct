plugins {
    id("conventions.kotlin-without-tests")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
}

dependencies {
    api(libs.viaduct.shared.apiannotations)
    api(libs.graphql.java)
    implementation(libs.kotlinx.coroutines.core)
}
