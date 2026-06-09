plugins {
    `java-library`
    id("conventions.kotlin")
    `java-test-fixtures`
    id("conventions.kotlin-static-analysis")
    id("conventions.dokka")
    id("conventions.bcv-api")
    id("feature-app-contracts")
    id("feature-app-contract-tests")
    id("me.champeau.jmh").version("0.7.3")
}

viaductFeatureAppContracts {
    kotlin {
        contractsFrom(":tenant:api")
    }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.moduleName.set("tenant-api")
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestFixturesKotlin") {
    compilerOptions.moduleName.set("tenant-api_testFixtures")
}

val testFileBasedBootstrap by tasks.registering(Test::class) {
    description = "Runs all contract tests using the file-based bootstrap"
    group = "verification"
    val testSourceSet = sourceSets.named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("feature-app-contract-test")
    }
    environment("USE_FILE_BASED_BOOTSTRAP", "true")
}

tasks.named("check").configure {
    dependsOn(testFileBasedBootstrap)
}


dependencies {
    /** Viaduct dependencies **/
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.service.api)
    api(libs.viaduct.shared.apiannotations)
    implementation(libs.viaduct.shared.graphql)
    api(libs.viaduct.errors)
    implementation(libs.viaduct.shared.mapping)
    implementation(libs.viaduct.shared.apiannotations)

    /** External dependencies **/
    implementation(libs.graphql.java)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.databind)
    implementation(libs.kotlinx.coroutines.core)

    /** Test fixtures - Viaduct dependencies **/
    testFixturesApi(libs.viaduct.engine.api)
    testFixturesApi(libs.viaduct.engine.wiring)
    testFixturesApi(libs.viaduct.service.api)
    testFixturesApi(libs.viaduct.service.runtime)
    testFixturesApi(libs.viaduct.service.wiring)
    testFixturesApi(libs.viaduct.tenant.runtime)
    testFixturesApi(libs.viaduct.tenant.wiring)
    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(testFixtures(libs.viaduct.shared.graphql))
    testFixturesImplementation(libs.viaduct.engine.api)
    testFixturesImplementation(libs.viaduct.service.api)
    testFixturesImplementation(libs.viaduct.service.runtime)
    testFixturesImplementation(libs.viaduct.service.wiring)
    testFixturesImplementation(libs.viaduct.shared.graphql)
    testFixturesImplementation(libs.viaduct.tenant.runtime)
    testFixturesImplementation(libs.viaduct.tenant.wiring)
    testFixturesImplementation(libs.guice)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.coroutines.jdk8)

    /** Test fixtures - External dependencies **/
    testFixturesRuntimeOnly(libs.kotlin.reflect)

    /** Test dependencies - Viaduct **/
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(libs.viaduct.shared.apiannotations)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.shared.arbitrary))
    testImplementation(testFixtures(libs.viaduct.shared.mapping))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.engine.api))

    /** Test dependencies - External **/
    testImplementation(libs.assertj.core)
    testImplementation(libs.graphql.java.extension)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)

    /** JMH dependencies **/
    jmh(libs.jmh.annotation.processor)
    jmhAnnotationProcessor(libs.jmh.annotation.processor)
    jmhApi(libs.jmh.core)
}
