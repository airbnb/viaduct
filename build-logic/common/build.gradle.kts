plugins {
    kotlin("jvm")
}

group = "com.airbnb.viaduct"

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}

tasks.test {
    useJUnitPlatform()
}
