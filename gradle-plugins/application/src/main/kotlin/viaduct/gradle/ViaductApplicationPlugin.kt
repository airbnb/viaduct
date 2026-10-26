package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import viaduct.gradle.ViaductPluginCommon.prettyPath

abstract class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
            pluginManager.apply(ViaductKotlinApplicationPlugin::class.java)
            pluginManager.apply(ViaductJavaApplicationPlugin::class.java)
            extensions.add(
                VIADUCT_APPLICATION_OUTPUTS_EXTENSION_NAME,
                ViaductApplicationOutputProviders(
                    centralSchemaDirectory = extensions.getByType(ViaductApplicationConventions::class.java).centralSchemaDirectory,
                    kotlinGrtJar = tasks.named("generateViaductGRTs", Jar::class.java).flatMap { it.archiveFile },
                    javaGrtJar = tasks.named("generateViaductJavaGRTs", Jar::class.java).flatMap { it.archiveFile },
                ),
            )
            validateJvmPluginApplied()
        }

    private fun Project.validateJvmPluginApplied() {
        afterEvaluate {
            if (configurations.findByName("api") == null || configurations.findByName("runtimeOnly") == null) {
                throw GradleException(
                    "Project ${prettyPath()} applies 'com.airbnb.viaduct.application-gradle-plugin' but no " +
                        "Java/Kotlin JVM plugin (e.g. 'org.jetbrains.kotlin.jvm' or 'java-library') is applied. " +
                        "Add one to the project's plugins block.",
                )
            }
        }
    }

    companion object {
        const val BUILTIN_SCHEMA_FILE = ViaductApplicationConventionsPlugin.BUILTIN_SCHEMA_FILE
    }
}
