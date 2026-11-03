plugins {
    id("conventions.kotlin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
}

val alphaFixtureDependencies = configurations.dependencyScope("alphaFixtureDependencies")
val alphaResolverBases = configurations.resolvable("alphaResolverBases") {
    extendsFrom(alphaFixtureDependencies.get())
    isTransitive = false
}
dependencies {
    add(alphaFixtureDependencies.name, project(mapOf("path" to ":multi-project:alpha", "configuration" to "fixtureResolverBases")))
}

val betaFixtureDependencies = configurations.dependencyScope("betaFixtureDependencies")
val betaResolverBases = configurations.resolvable("betaResolverBases") {
    extendsFrom(betaFixtureDependencies.get())
    isTransitive = false
}
dependencies {
    add(betaFixtureDependencies.name, project(mapOf("path" to ":multi-project:beta", "configuration" to "fixtureResolverBases")))
}

val copyAlphaResolverBases = tasks.register<Sync>("copyAlphaResolverBases") {
    from(alphaResolverBases)
    into(layout.buildDirectory.dir("fixture-outputs/alpha"))
}
val copyBetaResolverBases = tasks.register<Sync>("copyBetaResolverBases") {
    from(betaResolverBases)
    into(layout.buildDirectory.dir("fixture-outputs/beta"))
}

tasks.withType<Test>().configureEach {
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
    inputs.dir(copyAlphaResolverBases.map { it.destinationDir }).withPropertyName("alphaResolverBases").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("alphaResolverBasesDir", layout.buildDirectory.dir("fixture-outputs/alpha").get().asFile.absolutePath)
    inputs.dir(copyBetaResolverBases.map { it.destinationDir }).withPropertyName("betaResolverBases").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("betaResolverBasesDir", layout.buildDirectory.dir("fixture-outputs/beta").get().asFile.absolutePath)
}
