plugins {
    `java-library`
    alias(libs.plugins.viaduct.application)
    alias(libs.plugins.viaduct.module.java)
    application
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

viaductModule {
    modulePackageSuffix.set("resolvers")
}

dependencies {
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)
    implementation(libs.viaduct.javaapi.api)
    implementation(libs.viaduct.javaapi.runtime)

    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.databind)

    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.reactive.streams)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Use test fixtures bundle
    testImplementation(libs.viaduct.test.fixtures)
}

application {
    mainClass.set("com.example.viadapp.ViaductApplication")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Tar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<Zip> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
