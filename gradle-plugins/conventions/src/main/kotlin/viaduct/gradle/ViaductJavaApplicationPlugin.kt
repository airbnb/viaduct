package viaduct.gradle

import javaGrtClassesDirectory
import javaGrtSourcesDirectory
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.register
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaGRTCompileClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.task.GenerateJavaGRTSourcesTask

@InternalApi
class ViaductJavaApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
            val conventions = extensions.getByType(ViaductApplicationConventions::class.java)
            val grt = setupJavaGenerateGRTsTask(conventions.centralSchemaDirectory)
            configureIdeaIntegration(grt)
            ViaductGRTSupport.setupApplicationDependencies(
                this,
                conventions.modules,
                ViaductGRTLanguage.JAVA,
                grt.flatMap { it.archiveFile },
            )
            ViaductGRTSupport.publishVariants(
                this,
                ViaductGRTLanguage.JAVA,
                grt.flatMap { it.archiveFile },
                pluginVersion(ViaductJavaApplicationPlugin::class.java),
            )
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

        val generateGRTsTask = tasks.register<Jar>(ViaductGRTLanguage.JAVA.jarTaskName) {
            description = "Package compiled Java GraphQL Runtime Type (GRT) classes into a jar."

            archiveBaseName.set("viaduct-java-grt")
            includeEmptyDirs = false

            from(compileGRTJavaTask.map { it.destinationDirectory })
        }

        return generateGRTsTask
    }
}
