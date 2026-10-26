package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import javaGrtClassesDirectory
import javaGrtSourcesDirectory
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.register
import schemaScopingManifestDirectory
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaGRTCompileClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.prettyPath
import viaduct.gradle.ViaductPluginCommon.validateApplicationProjectPlacement
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.AssembleSchemaPartitionTask
import viaduct.gradle.task.AssembleTenantModuleConfigFileTask
import viaduct.gradle.task.GenerateExecutionRegistryResourceIndexTask
import viaduct.gradle.task.GenerateGRTClassFilesTask
import viaduct.gradle.task.GenerateJavaGRTSourcesTask
import viaduct.gradle.task.GenerateSchemaResourceIndexTask
import viaduct.gradle.task.GenerateSchemaScopingManifestTask
import viaduct.gradle.task.ValidateSchemaExtensionsTask
import viaduct.scoping.SchemaScopeDefinitions

class ViaductKotlinApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
        val conventions = extensions.getByType(ViaductApplicationConventions::class.java)
        val grt = setupKotlinGenerateGRTsTask(conventions.centralSchemaDirectory)
        configureIdeaIntegration(grt)
        mapOf("Api" to Usage.JAVA_API, "Runtime" to Usage.JAVA_RUNTIME).forEach { (suffix, usage) ->
            configurations.consumable("viaductKotlinGrt${suffix}Elements") {
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, usage))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
                }
                outgoing.capability("com.airbnb.viaduct:kotlin-grt:${pluginVersion(ViaductKotlinApplicationPlugin::class.java)}")
                outgoing.artifact(grt.flatMap { it.archiveFile })
            }
        }
    }

    private fun Project.setupKotlinGenerateGRTsTask(centralSchemaDirectory: Provider<Directory>,): TaskProvider<Jar> {
        val version = pluginVersion(ViaductKotlinApplicationPlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)

        val generateGRTClassesTask = tasks.register<GenerateGRTClassFilesTask>("generateViaductGRTClassFiles") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            grtClassesDirectory.set(grtClassesDirectory())
            schemaFiles.setFrom(centralSchemaDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") } })
            classpath.setFrom(codegenClasspath)
            mainClass.set(CODEGEN_MAIN_CLASS)
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductGRTs") {
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
