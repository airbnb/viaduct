plugins {
    id("conventions.kotlin")
    id("com.airbnb.viaduct.application-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
}

val resolverFixtureDependencies = configurations.dependencyScope("resolverFixtureDependencies")
val resolverBases = configurations.resolvable("resolverBases") {
    extendsFrom(resolverFixtureDependencies.get())
    isTransitive = false
}
dependencies {
    add(resolverFixtureDependencies.name, project(mapOf("path" to ":two-project:resolvers", "configuration" to "fixtureResolverBases")))
}

val copyResolverBases = tasks.register<Sync>("copyResolverBases") {
    from(resolverBases)
    into(layout.buildDirectory.dir("fixture-outputs/resolver"))
}

tasks.withType<Test>().configureEach {
    dependsOn("validateViaductSchemaExtensions")
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
    inputs.dir(copyResolverBases.map { it.destinationDir }).withPropertyName("resolverBases").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("resolverBasesDir", layout.buildDirectory.dir("fixture-outputs/resolver").get().asFile.absolutePath)
}
