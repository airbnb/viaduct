plugins {
    id("conventions.kotlin")
}

dependencies {
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation(libs.junit.params)

    testImplementation(gradleTestKit())
}

// Run test from root build with:
//      export VIADUCT_VERSION_OVERRIDE="0.0.0"; ./gradlew :gradle-plugins:functional-tests:test
// sucks...
gradle.parent?.let {
    it.projectsEvaluated {
        tasks.test {
            systemProperty("viaduct.version", it.rootProject.version)

            dependsOn(it.rootProject.tasks.named("publishToMavenLocal"))
        }
    }
}