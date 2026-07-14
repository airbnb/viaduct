plugins {
    id("conventions.kotlin")
    id("conventions.ksp")
    id("com.airbnb.viaduct.application-gradle-plugin")
    id("com.airbnb.viaduct.module-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
}

tasks.withType<Test>().configureEach {
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
}
