plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("me.champeau.jmh").version("0.7.3")
}

dependencies {
    api(libs.graphql.java)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.viaduct.shared.apiannotations)

    testImplementation(libs.kotest.assertions.shared)
    testImplementation(libs.guava.testlib)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.konsist)

    jmh(libs.jmh.annotation.processor)
    jmh(libs.jmh.core)
}
