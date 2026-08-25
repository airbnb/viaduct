plugins {
    `java-library`
    id("conventions.java")
    id("com.airbnb.viaduct.module-java-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:javaapi-api")
    implementation("com.airbnb.viaduct:javaapi-runtime")
}
