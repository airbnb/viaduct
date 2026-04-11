plugins {
    `java-library`
    id("conventions.java")
    id("conventions.dokka")
}

description = "Java Tenant API interfaces"

dependencies {
    api(libs.viaduct.engine.api)

    compileOnly(libs.jspecify)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.assertj.core)
}
