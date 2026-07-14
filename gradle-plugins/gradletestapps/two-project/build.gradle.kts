plugins {
    id("conventions.kotlin")
    id("com.airbnb.viaduct.application-gradle-plugin")
}

viaductApplication {
    modulePackagePrefix.set("com.example.execution.twoproject")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
}

tasks.withType<Test>().configureEach {
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
}
