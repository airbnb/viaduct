plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":model"))
    api(project(":semantics"))
    api(testFixtures(project(":model")))
    api("io.kotest:kotest-property-jvm:5.9.1")

    implementation("com.graphql-java:graphql-java:26.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.3")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(testFixtures(project(":semantics")))
    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
