plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("me.champeau.jmh").version("0.7.3")
}

dependencies {
    api(libs.graphql.java)
    api(libs.viaduct.shared.apiannotations)

    implementation(libs.caffeine)
    implementation(libs.classgraph)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.assertions.shared)
    testImplementation(libs.guava.testlib)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotest.property.jvm)

    jmh(libs.jmh.annotation.processor)
    jmh(libs.jmh.core)
}
