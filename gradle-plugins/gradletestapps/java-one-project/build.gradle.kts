plugins {
    // `java-library` on top of `conventions.java`: the application plugin's `validateJvmPluginApplied`
    // requires an `api` configuration, which the plain `java` plugin does not create.
    `java-library`
    id("conventions.java")
    id("conventions.test-retry")
    id("com.airbnb.viaduct.application-gradle-plugin")
    id("com.airbnb.viaduct.module-java-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
    implementation("com.airbnb.viaduct:javaapi-api")
    implementation("com.airbnb.viaduct:javaapi-runtime")
}

// The smoke test asserts on what the module actually ships: the assembled registry is packaged by
// `jar`, and the raw APT descriptors must not be. `test` does not build the jar on its own, and
// build/libs also holds the application plugin's GRT jars, so hand the test the exact path.
val moduleJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }

tasks.withType<Test>().configureEach {
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
    systemProperty("moduleJar", moduleJar.get().asFile.absolutePath)

    inputs.file(moduleJar)
}
