package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.validateModuleProjectPlacement
import viaduct.gradle.task.AssembleTenantModuleConfigFileTask
import viaduct.gradle.task.GenerateJavaResolverBasesTask

class ViaductJavaModulePlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            val topology = validateModuleProjectPlacement("com.airbnb.viaduct.module-java-gradle-plugin")
            val moduleLayout = ViaductModulePluginSupport.modulePackageLayout(this, topology)

            ViaductModulePluginSupport.configureDirectModuleDependencyChecks(this, topology)

            val viaductApplication = ViaductModulePluginSupport.setupViaductApplicationConfiguration(this)
            val grtIncomingCfg = ViaductModulePluginSupport.createGRTIncomingConfiguration(
                this,
                ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_INCOMING,
                ViaductPluginCommon.Kind.JAVA_GRT_CLASSES,
            )

            val assembleSchemaPartitionTask =
                ViaductModulePluginSupport.setupAssembleSchemaPartitionTask(this, moduleLayout)
            ViaductModulePluginSupport.setupOutgoingConfigurationForPartitionSchema(this, assembleSchemaPartitionTask)

            val centralSchemaIncomingCfg =
                ViaductModulePluginSupport.setupIncomingConfigurationForCentralSchema(this, viaductApplication)
            val generateResolverBasesTask = setupGenerateResolverBasesTask(moduleLayout, centralSchemaIncomingCfg)
            val assembleModuleConfigTask = setupAptRegistryExtractor(moduleLayout, centralSchemaIncomingCfg)

            ViaductModulePluginSupport.wireToTopologyApplicationProject(
                this,
                topology,
                viaductApplication,
                centralSchemaIncomingCfg,
                grtIncomingCfg,
                ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_OUTGOING,
                ViaductApplicationOutputProviders::javaGrtJar,
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
                description = "Run all Viaduct code generation for this module: generates abstract Java resolver base classes and assembles the runtime resolver registry."

                dependsOn(generateResolverBasesTask, assembleModuleConfigTask)
            }
        }

    /**
     * Wires the Java registry-extractor APT and the config assembly that consumes its output — the
     * Java counterpart of the Kotlin module plugin's KSP registry extractor. The APT records each
     * `@Resolver` into a per-source descriptor; the assembly task consolidates those into the
     * `META-INF/viaduct/modules/<pkg>.json` registry the engine bootstraps from.
     *
     * Unlike KSP, javac's [Filer] writes resources to the class output directory, so descriptors
     * are read from there directly and no bridging copy task is needed.
     */
    private fun Project.setupAptRegistryExtractor(
        moduleLayout: ViaductModulePackageLayout,
        centralSchemaIncomingCfg: Configuration,
    ): TaskProvider<AssembleTenantModuleConfigFileTask> {
        val version = pluginVersion(ViaductJavaModulePlugin::class.java)

        pluginManager.withPlugin("java") {
            dependencies.add("annotationProcessor", "com.airbnb.viaduct:javaapi-buildtime:$version")
        }

        val assembleTask = tasks.register<AssembleTenantModuleConfigFileTask>("assembleViaductModuleConfigFile") {
            description = "Assembles the module's runtime resolver configuration from APT-generated descriptors. Re-run after adding or modifying @Resolver-annotated classes."

            // javac's Filer writes the descriptors below whatever `compileJava` is configured to use
            // as its class output, so follow that task rather than assuming the default layout.
            descriptorDir.set(
                tasks.named<JavaCompile>("compileJava").flatMap { compile ->
                    compile.destinationDirectory.dir(DESCRIPTOR_DIR_NAME)
                }
            )
            executorFactory.set(AssembleTenantModuleConfigFileTask.JAVA_EXECUTOR_FACTORY)
            apiName.set(AssembleTenantModuleConfigFileTask.JAVA_API_NAME)
            codegenClasspath.from(createOrGetCodegenClasspath(version))
            centralSchemaFiles.from(
                centralSchemaIncomingCfg.incoming.artifactView {}.files.asFileTree.matching {
                    include("**/*.graphqls")
                }
            )
            outputDir.set(layout.buildDirectory.dir("generated-resources/viaduct-registry"))
            tenantPackage.set(moduleLayout.fullTenantPackage)
            tenantPackagePrefix.set(moduleLayout.modulePackagePrefix)

            dependsOn(tasks.named("compileJava"))
        }

        pluginManager.withPlugin("java") {
            val javaExt = extensions.getByType(JavaPluginExtension::class.java)
            javaExt.sourceSets.named("main") {
                resources.srcDir(assembleTask.flatMap { it.outputDir })
            }
            tasks.named("processResources").configure { dependsOn(assembleTask) }

            // javac's Filer writes the per-source descriptors into the class output, so without this
            // these build intermediates ship in the JAR alongside the assembled registry.
            tasks.named<Jar>("jar").configure { exclude("$DESCRIPTOR_DIR_NAME/**") }
        }

        return assembleTask
    }

    private fun Project.setupGenerateResolverBasesTask(
        moduleLayout: ViaductModulePackageLayout,
        centralSchemaIncomingCfg: Configuration
    ): TaskProvider<GenerateJavaResolverBasesTask> {
        val version = pluginVersion(ViaductJavaModulePlugin::class.java)
        val codegenClasspath = createOrGetJavaCodegenClasspath(version)
        return tasks.register<GenerateJavaResolverBasesTask>("generateViaductResolverBases") {
            centralSchemaFiles.from(
                centralSchemaIncomingCfg.incoming.artifactView {}.files.asFileTree.matching { include("**/*.graphqls") }
            )
            classpath.setFrom(codegenClasspath)
            wireToModuleLayout(moduleLayout)
        }
    }

    private companion object {
        /**
         * Directory, relative to javac's class output, that `JavaRegistryExtractorProcessor` writes
         * its per-source descriptors into. Must match `DESCRIPTOR_ROOT` in `:javaapi:registry-apt`.
         */
        const val DESCRIPTOR_DIR_NAME = "viaduct-registry"
    }
}
