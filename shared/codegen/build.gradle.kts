plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

tasks.test {
    jvmArgs = ((jvmArgs ?: emptyList<String>()) + "--add-opens=java.base/java.lang=ALL-UNNAMED")
}

dependencies {
    api(libs.javassist)
    api(libs.kotlinx.metadata.jvm)
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.utils)

    implementation(libs.antlr.st4)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.viaduct.shared.apiannotations)

    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotest.assertions.shared)
    testImplementation(libs.kotlin.reflect)
}
