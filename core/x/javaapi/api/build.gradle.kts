plugins {
    `java-library`
    id("conventions.java")
    id("conventions.dokka")
}

description = "Java Tenant API interfaces"

dependencies {
    compileOnly(libs.jspecify)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.assertj.core)
}
