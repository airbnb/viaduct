package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.validateModuleProjectPlacement
import viaduct.gradle.task.GenerateJavaResolverBasesTask

class ViaductJavaModulePlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            validateModuleProjectPlacement("com.airbnb.viaduct.module-java-gradle-plugin")

            val moduleExt = extensions.findByType(ViaductModuleExtension::class.java)
                ?: extensions.create("viaductModule", ViaductModuleExtension::class.java, objects)

            ViaductModulePluginSupport.configureDirectModuleDependencyChecks(this)
            ViaductModulePluginSupport.configureModulePackageSuffixConvention(this, moduleExt)

            val grtIncomingCfg = ViaductModulePluginSupport.createGRTIncomingConfiguration(
                this,
                ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_INCOMING,
                ViaductPluginCommon.Kind.JAVA_GRT_CLASSES,
            )

            val assembleSchemaPartitionTask =
                ViaductModulePluginSupport.setupAssembleSchemaPartitionTask(this, moduleExt)
            ViaductModulePluginSupport.setupOutgoingConfigurationForPartitionSchema(this, assembleSchemaPartitionTask)

            val centralSchemaIncomingCfg = ViaductModulePluginSupport.setupIncomingConfigurationForCentralSchema(this)
            val generateResolverBasesTask = setupGenerateResolverBasesTask(moduleExt, centralSchemaIncomingCfg)

            ViaductModulePluginSupport.wireToContainingApplicationProject(
                this,
                ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_INCOMING,
                ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_OUTGOING,
            )

            // GRT classes into source sets
            plugins.withId("java") {
                configurations.named("implementation").configure { extendsFrom(grtIncomingCfg) }
                configurations.named("testImplementation").configure { extendsFrom(grtIncomingCfg) }
            }
            pluginManager.withPlugin("java-test-fixtures") {
                configurations.named("testFixturesImplementation").configure { extendsFrom(grtIncomingCfg) }
            }

            // Generated resolver bases into Java `main` source set
            pluginManager.withPlugin("java") {
                val javaExt = extensions.getByType(JavaPluginExtension::class.java)
                javaExt.sourceSets.named("main") {
                    java.srcDir(generateResolverBasesTask.flatMap { it.outputDirectory })
                }
            }

            configureIdeaIntegration(generateResolverBasesTask)

            // Convenience task for module-level codegen
            tasks.register("viaductCodegen") {
                description = "Run all Viaduct code generation for this module: generates abstract Java resolver base classes."

                dependsOn(generateResolverBasesTask)
            }
        }

    private fun Project.setupGenerateResolverBasesTask(
        moduleExt: ViaductModuleExtension,
        centralSchemaIncomingCfg: Configuration
    ): TaskProvider<GenerateJavaResolverBasesTask> {
        val version = pluginVersion(ViaductJavaModulePlugin::class.java)
        val codegenClasspath = createOrGetJavaCodegenClasspath(version)
        val taskProvider = tasks.register<GenerateJavaResolverBasesTask>("generateViaductResolverBases") {
            centralSchemaFiles.from(
                centralSchemaIncomingCfg.incoming.artifactView {}.files.asFileTree.matching { include("**/*.graphqls") }
            )
            classpath.setFrom(codegenClasspath)
        }

        ViaductModulePluginSupport.validateContainingApplicationProject(
            this,
            "com.airbnb.viaduct.module-java-gradle-plugin",
        ) { appExt ->
            taskProvider.configure { wireToExtensions(moduleExt, appExt) }
        }

        return taskProvider
    }
}
