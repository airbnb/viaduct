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

class ViaductJavaApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
        val conventions = extensions.getByType(ViaductApplicationConventions::class.java)
        val grt = setupJavaGenerateGRTsTask(conventions.centralSchemaDirectory)
        configureIdeaIntegration(grt)
        mapOf("Api" to Usage.JAVA_API, "Runtime" to Usage.JAVA_RUNTIME).forEach { (suffix, usage) ->
            configurations.consumable("viaductJavaGrt${suffix}Elements") {
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, usage))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
                }
                outgoing.capability("com.airbnb.viaduct:java-grt:${pluginVersion(ViaductJavaApplicationPlugin::class.java)}")
                outgoing.artifact(grt.flatMap { it.archiveFile })
            }
        }
    }

    private fun Project.setupJavaGenerateGRTsTask(centralSchemaDirectory: Provider<Directory>): TaskProvider<Jar> {
        val version = pluginVersion(ViaductJavaApplicationPlugin::class.java)
        val codegenClasspath = createOrGetJavaCodegenClasspath(version)
        val grtCompileClasspath = createOrGetJavaGRTCompileClasspath(version)

        val generateGRTSourcesTask = tasks.register<GenerateJavaGRTSourcesTask>("generateViaductJavaGRTSources") {
            grtSourcesDirectory.set(javaGrtSourcesDirectory())
            schemaFiles.setFrom(
                centralSchemaDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") } }
            )
            classpath.setFrom(codegenClasspath)
        }

        val compileGRTJavaTask = tasks.register<JavaCompile>("compileViaductJavaGRTJava") {
            dependsOn(generateGRTSourcesTask)
            source = fileTree(generateGRTSourcesTask.flatMap { it.grtSourcesDirectory })
            destinationDirectory.set(javaGrtClassesDirectory())
            classpath = grtCompileClasspath
            options.isIncremental = true
        }

        // Gradle only sets the `javaCompiler` convention on source-set compile tasks, and a manually
        // registered `JavaCompile` falls back to the build JVM rather than `java.toolchain`. Without
        // this, an application that pins a toolchain older than its build JVM gets GRT bytecode that
        // its own `compileJava` cannot read. When no toolchain is declared this resolves to the same
        // build JVM used today, so existing consumers are unaffected.
        pluginManager.withPlugin("java") {
            val toolchain = extensions.getByType(JavaPluginExtension::class.java).toolchain
            val toolchains = extensions.getByType(JavaToolchainService::class.java)
            compileGRTJavaTask.configure {
                javaCompiler.set(toolchains.compilerFor(toolchain))
            }
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductJavaGRTs") {
            description = "Package compiled Java GraphQL Runtime Type (GRT) classes into a jar."

            archiveBaseName.set("viaduct-java-grt")
            includeEmptyDirs = false

            from(compileGRTJavaTask.map { it.destinationDirectory })
        }

        return generateGRTsTask
    }
}
