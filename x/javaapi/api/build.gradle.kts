plugins {
    id("conventions.java")
}

description = "Java Tenant API interfaces"

dependencies {
    compileOnly(libs.jspecify)

    testImplementation(libs.assertj.core)
}
