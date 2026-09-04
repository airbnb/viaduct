package viaduct.gradle.defaultschema

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import viaduct.gradle.utils.capitalize

/**
 * Plugin that automatically sets up default schema extraction for Viaduct projects.
 *
 * This plugin:
 * 1. Creates a task to extract default_schema.graphqls to a predictable build location
 * 2. Adds the output directory to test resources so tests can access it via classpath
 * 3. Provides a way for other plugins to depend on the default schema file
 */
abstract class DefaultSchemaPlugin : Plugin<Project> {
    companion object {
        const val TASK_NAME = "extractDefaultSchema"
        const val DEFAULT_SCHEMA_BUILD_PATH = "generated-resources/viaduct"
        const val DEFAULT_SCHEMA_FILENAME = "default_schema.graphqls"

        /**
         * Ensures the default schema plugin is applied to the project.
         * This allows other plugins to easily ensure the default schema is available.
         */
        fun ensureApplied(project: Project) {
            if (!project.plugins.hasPlugin(DefaultSchemaPlugin::class.java)) {
                project.pluginManager.apply(DefaultSchemaPlugin::class.java)
            }
        }

        /**
         * Returns the generated resources directory containing the default schema.
         * Callers targeting a non-"test" source set can use this to wire the
         * schema resources into their target source set.
         */
        fun getGeneratedResourcesDir(project: Project): Provider<Directory> {
            return project.layout.buildDirectory.dir(DEFAULT_SCHEMA_BUILD_PATH)
        }

        /**
         * Returns a lazy provider for the extracted default schema file, establishing
         * a proper task dependency on [TASK_NAME] so consumers can declare it as
         * a typed [org.gradle.api.tasks.InputFile] input.
         */
        fun getDefaultSchemaFileProvider(project: Project): Provider<RegularFile> = project.tasks.named<DefaultSchemaOutputTask>(TASK_NAME).flatMap { it.defaultSchemaFile }

        /**
         * Wires the default schema generated resources into the given source set and
         * ensures the corresponding processResources task depends on schema extraction.
         * No-op if [sourceSetName] is "test" (already wired by [apply]).
         */
        fun wireToSourceSet(
            project: Project,
            sourceSetName: String
        ) {
            if (sourceSetName == "test") return
            wireGeneratedResourcesIntoSourceSet(
                project,
                sourceSetName,
                getGeneratedResourcesDir(project),
                project.tasks.named(TASK_NAME)
            )
        }

        /** Shared by [wireToSourceSet] and other plugins (e.g. viaduct-classdiff) with their own generated resources. */
        fun wireGeneratedResourcesIntoSourceSet(
            project: Project,
            sourceSetName: String,
            resourcesDir: Provider<Directory>,
            dependency: TaskProvider<*>
        ) {
            val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
            javaExt.sourceSets.named(sourceSetName).configure { resources.srcDir(resourcesDir) }
            val processResourcesTaskName = "process${sourceSetName.capitalize()}Resources"
            project.tasks.named(processResourcesTaskName).configure { dependsOn(dependency) }
        }
    }

    override fun apply(project: Project) {
        // Register the task to extract default schema
        val defaultSchemaTask = project.tasks.register<DefaultSchemaOutputTask>(TASK_NAME) {
            defaultSchemaFile.set(
                project.layout.buildDirectory.file("$DEFAULT_SCHEMA_BUILD_PATH/$DEFAULT_SCHEMA_FILENAME")
            )
        }

        // Add the output directory to test resources when java plugin is applied
        project.plugins.withId("java") {
            val javaExtension = project.extensions.getByType<JavaPluginExtension>()
            val testSourceSet = javaExtension.sourceSets.getByName("test")

            // Add the generated resources directory to test resources
            // This makes the default schema available to tests via classpath
            val generatedResourcesDir = project.layout.buildDirectory.dir(DEFAULT_SCHEMA_BUILD_PATH)
            testSourceSet.resources.srcDir(defaultSchemaTask.map { generatedResourcesDir })

            project.logger.info("Added default schema output directory to test resources")
        }

        // Integrate with common build lifecycle tasks
        project.tasks.named("processResources").configure {
            dependsOn(defaultSchemaTask)
        }
    }
}
