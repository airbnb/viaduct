plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
}

description = "Provides PROJECT level convention plugins for the build"

detekt {
    source.setFrom("src/main/kotlin")
    config.setFrom(
        layout.projectDirectory.dir("..").file("detekt.yml"),
        layout.projectDirectory.dir("..").file("detekt-viaduct.yml"),
    )
    ignoreFailures = true
}

dependencies {
    detektPlugins(project(":build-common"))
    // conventions dependencies
    implementation(libs.kotlinx.binary.compatibility.validator)
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.gradle.maven.publish))
    implementation(plugin(libs.plugins.detekt))
    implementation(plugin(libs.plugins.ktlintPlugin))
    implementation(plugin(libs.plugins.dokka))
    implementation(plugin(libs.plugins.dokkaJavaDoc))
    implementation(plugin(libs.plugins.spotbugs))
    implementation(plugin(libs.plugins.shadow))
    implementation(plugin(libs.plugins.cyclonedx.bom))
    implementation(plugin(libs.plugins.jk1.license.report))
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
