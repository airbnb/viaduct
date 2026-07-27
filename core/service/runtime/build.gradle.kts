plugins {
    id("conventions.kotlin")
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.moduleName.set("service-runtime")
}

dependencies {
    api(libs.micrometer.core)

    implementation(libs.viaduct.service.api)
    implementation(libs.graphql.java)
    implementation(libs.guice)


    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.engine.wiring)

    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.apiannotations)
    implementation(libs.caffeine)
    implementation(libs.classgraph)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module)
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)

    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.service.api))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.jspecify)
    testImplementation(libs.kotlinx.coroutines.test)
}
