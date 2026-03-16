plugins {
    id("conventions.kotlin-without-tests")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    api(libs.viaduct.shared.apiannotations)
    api(libs.graphql.java)
    implementation(libs.kotlinx.coroutines.core)
}
