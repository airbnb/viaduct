plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotest.assertions.shared)
}
