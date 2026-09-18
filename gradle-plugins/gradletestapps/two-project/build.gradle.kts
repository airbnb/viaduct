plugins {
    id("conventions.kotlin")
    id("com.airbnb.viaduct.application-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
}

tasks.withType<Test>().configureEach {
    dependsOn("validateViaductSchemaExtensions")
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
    systemProperty(
        "resolverBuildDir",
        project(":two-project:resolvers").layout.buildDirectory.asFile.get().absolutePath,
    )
}
