plugins {
    `kotlin-dsl`
}

description = "Provides PROJECT level convention plugins for the build"

dependencies {
    // conventions dependencies
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.gradle.maven.publish))
    implementation(plugin(libs.plugins.detekt))
    implementation(plugin(libs.plugins.ktlintPlugin))
    implementation(plugin(libs.plugins.dokka))
    compileOnly(libs.detekt.api)

    // buildroot dependencies
    implementation(plugin(libs.plugins.dependency.analysis))

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
