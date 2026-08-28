plugins {
    `java-library`
}

dependencies {
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.http)
    annotationProcessor(libs.micronaut.inject.java)
}
