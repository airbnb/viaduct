package viaduct.gradle

import grtClassesDirectory
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.task.GenerateGRTClassFilesTask

@InternalApi
class ViaductKotlinApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
            val conventions = extensions.getByType(ViaductApplicationConventions::class.java)
            val grt = setupKotlinGenerateGRTsTask(conventions.centralSchemaDirectory)
            configureIdeaIntegration(grt)
            ViaductGRTSupport.setupApplicationDependencies(
                this,
                conventions.modules,
                ViaductGRTLanguage.KOTLIN,
                grt.flatMap { it.archiveFile },
            )
            ViaductGRTSupport.publishVariants(
                this,
                ViaductGRTLanguage.KOTLIN,
                grt.flatMap { it.archiveFile },
                pluginVersion(ViaductKotlinApplicationPlugin::class.java),
            )
        }

    private fun Project.setupKotlinGenerateGRTsTask(centralSchemaDirectory: Provider<Directory>): TaskProvider<Jar> {
        val version = pluginVersion(ViaductKotlinApplicationPlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)

        val generateGRTClassesTask = tasks.register<GenerateGRTClassFilesTask>("generateViaductGRTClassFiles") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            grtClassesDirectory.set(grtClassesDirectory())
            schemaFiles.setFrom(centralSchemaDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") } })
            classpath.setFrom(codegenClasspath)
            mainClass.set(CODEGEN_MAIN_CLASS)
        }

        val generateGRTsTask = tasks.register<Jar>(ViaductGRTLanguage.KOTLIN.jarTaskName) {
            description = "Package compiled Kotlin GraphQL Runtime Type (GRT) classes into a jar."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            from(generateGRTClassesTask.flatMap { it.grtClassesDirectory })
        }

        return generateGRTsTask
    }

    private companion object {
        const val CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
    }
}
