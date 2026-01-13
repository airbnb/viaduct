plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

tasks.withType<Test>().configureEach {
    jvmArgs = listOf("-Xmx4g")
}

dependencies {
    api(libs.graphql.java)
    api(libs.kotest.property.jvm)
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.viaductschema)
    api(libs.viaduct.shared.mapping)
    api(libs.viaduct.shared.apiannotations)

    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.kotest.common.jvm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.kotest.assertions.shared)
}
