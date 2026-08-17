import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
}

description = "Provides PROJECT level convention plugins for the build"

// Treat Kotlin compiler warnings as errors across build-logic (the convention plugins themselves),
// matching the Bazel -Werror. `allprojects` because the root project holds Kotlin, not just the subprojects.
// Match KotlinCompile directly: root + :build-test-support are kotlin-dsl, :build-common / :build-ktlint are kotlin("jvm")
allprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors = true
        }
    }
}

detekt {
    source.setFrom("src/main/kotlin")
    config.setFrom(
        layout.projectDirectory.dir("..").file("detekt.yml"),
        layout.projectDirectory.dir("..").file("detekt-viaduct.yml"),
    )
}

dependencies {
    detektPlugins(project(":build-common"))
    // conventions dependencies
    implementation(libs.kotlinx.binary.compatibility.validator)
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.ksp))
    implementation(plugin(libs.plugins.gradle.maven.publish))
    implementation(plugin(libs.plugins.detekt))
    implementation(plugin(libs.plugins.errorprone))
    implementation(plugin(libs.plugins.ktlintPlugin))
    implementation(plugin(libs.plugins.dokka))
    implementation(plugin(libs.plugins.dokkaJavaDoc))
    implementation(plugin(libs.plugins.shadow))
    implementation(plugin(libs.plugins.spotless))
    implementation(plugin(libs.plugins.cyclonedx.bom))
    implementation(plugin(libs.plugins.jk1.license.report))
    implementation(plugin(libs.plugins.test.retry))
    compileOnly(libs.detekt.api)

    // settings dependencies
    implementation(plugin(libs.plugins.develocity))
    implementation(plugin(libs.plugins.foojay.resolver.convention))
    implementation(plugin(libs.plugins.dokka))
}

/**
 * Helper function that transforms a Gradle Plugin alias from a Version Catalog into a valid dependency notation
 */
fun plugin(plugin: Provider<PluginDependency>) =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
