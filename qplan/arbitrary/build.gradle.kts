plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-library`
}

dependencies {
    api(project(":model"))
    api(project(":semantics"))
    api(testFixtures(project(":model")))
    api(libs.kotest.property.jvm)

    implementation(libs.graphql.java)
    implementation(libs.jackson.module)

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(testFixtures(project(":semantics")))
    testImplementation(kotlin("test-junit5"))
}
