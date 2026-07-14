plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.application)
    application
    jacoco
}

viaductApplication {
    // The starter's `viadapp` module ships an SDL with `@scope(to: ["default"])` on its
    // Query/Mutation extensions, so opt into schema scoping. Post-slice-4 the build-time
    // central-SDL assembler only emits the `@scope` directive definition when the app
    // declares a scope universe; without this block the assembler drops `directive @scope`
    // and graphql-java rejects the tenant SDL with "Unknown directive '@scope'".
    declareScoping {
        scopes("default")
    }
}

application {
    mainClass.set("com.example.viadapp.AppKt")
}

dependencies {
    // Micronaut DI (no HTTP server) - used in production
    ksp(libs.micronaut.inject.kotlin)
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.context)

    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    // Viaduct's fat jars exclude these (bring-your-own-version),
    // so a runnable consumer must put them on its own runtime classpath.
    runtimeOnly(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.kotlinx.coroutines.jdk8)
    runtimeOnly(libs.reactive.streams)

    implementation(libs.kotlin.reflect)

    implementation(project(":common"))

    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)
    // Viaduct's scalar definitions (ExtendedScalars) are needed at runtime to assemble the
    // schema but aren't pulled in transitively, so declare it on the runtime classpath. This is
    // also what lets the packaged dist (installDist / smokeTestDist) start; tests inherit it.
    runtimeOnly(libs.graphql.java.extended.scalars)

    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Build the application distribution and run the produced start script, so the packaged
// classpath is actually assembled and the JVM is started. An in-process `test` never exercises
// the dist; this catches packaging/classpath regressions before publish. Wired into `test`
// so the existing CI invocation (`./gradlew test`) runs it.
val smokeTestDist by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the application dist and runs its start script (installDist smoke test)."
    dependsOn("installDist")
    val startScript = layout.buildDirectory.file("install/${rootProject.name}/bin/${rootProject.name}")
    inputs.file(startScript)
    commandLine(startScript.get().asFile.absolutePath)
}

tasks.named("test") {
    dependsOn(smokeTestDist)
}
