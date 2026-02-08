package viaduct.gradle

import centralSchemaDirectoryName
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import schemaPartitionDirectory
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.task.AssembleSchemaPartitionTask
import viaduct.gradle.task.GenerateResolverBasesTask

open class ViaductModuleExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name suffix for this module (can be empty). */
    val modulePackageSuffix = objects.property(String::class.java)
}

class ViaductModulePlugin : Plugin<Project> {
    companion object {
        private const val RESOLVER_CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.ViaductGenerator\$Main"
    }

    override fun apply(project: Project): Unit =
        with(project) {
            val moduleExt = extensions.create("viaductModule", ViaductModuleExtension::class.java, objects)

            pluginManager.withPlugin("java") { enforceNoDirectModuleDeps() }
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { enforceNoDirectModuleDeps() }

            pluginManager.withPlugin("com.airbnb.viaduct.application-gradle-plugin") {
                moduleExt.modulePackageSuffix.convention("")
            }

            val grtIncomingCfg = configurations.create(ViaductPluginCommon.Configs.GRT_CLASSES_INCOMING).apply {
                description = "Resolvable configuration for the GRT jar file."
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.GRT_CLASSES)
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                    attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        objects.named(LibraryElements::class.java, LibraryElements.JAR)
                    )
                }
            }

            val assembleSchemaPartitionTask = setupAssembleSchemaPartitionTask(moduleExt)
            setupOutgoingConfigurationForPartitionSchema(assembleSchemaPartitionTask)

            val centralSchemaIncomingCfg = setupIncomingConfigurationForCentralSchema()
            val generateResolverBasesTask = setupGenerateResolverBasesTask(moduleExt, centralSchemaIncomingCfg)

            // Register wiring with the root application plugin if present
            rootProject.pluginManager.withPlugin("com.airbnb.viaduct.application-gradle-plugin") {
                rootProject.dependencies.add(
                    ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING,
                    rootProject.dependencies.project(
                        mapOf(
                            "path" to project.path,
                            "configuration" to ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING
                        )
                    )
                )
                rootProject.dependencies.add("runtimeOnly", project)

                dependencies.add(
                    ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING,
                    project.dependencies.project(
                        mapOf(
                            "path" to rootProject.path,
                            "configuration" to ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING
                        )
                    )
                )

                dependencies.add(
                    ViaductPluginCommon.Configs.GRT_CLASSES_INCOMING,
                    project.dependencies.project(
                        mapOf(
                            "path" to rootProject.path,
                            "configuration" to ViaductPluginCommon.Configs.GRT_CLASSES_OUTGOING
                        )
                    )
                )
            }

            // GRT classes into source sets
            plugins.withId("java") {
                configurations.named("implementation").configure { extendsFrom(grtIncomingCfg) }
                configurations.named("testImplementation").configure { extendsFrom(grtIncomingCfg) }
            }
            pluginManager.withPlugin("java-test-fixtures") {
                configurations.named("testFixturesImplementation").configure { extendsFrom(grtIncomingCfg) }
            }

            // Generated resolver bases into Kotlin source set
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                val kotlinExt = extensions.getByType(KotlinJvmProjectExtension::class.java)
                kotlinExt.sourceSets.named("main") {
                    kotlin.srcDir(generateResolverBasesTask.flatMap { it.outputDirectory })
                }

                kotlinExt.compilerOptions {
                    freeCompilerArgs.add("-Xcontext-receivers")
                }
            }

            configureIdeaIntegration(generateResolverBasesTask)

            // Convenience task for module-level codegen
            tasks.register("viaductCodegen") {
                group = "viaduct"
                description = "Run Viaduct code generation for this module (GRTs + resolver bases)"

                dependsOn(generateResolverBasesTask)
            }
        }

    private fun Project.setupAssembleSchemaPartitionTask(moduleExt: ViaductModuleExtension): TaskProvider<AssembleSchemaPartitionTask> {
        return tasks.register<AssembleSchemaPartitionTask>("prepareViaductSchemaPartition") {
            graphqlSrcDir.set(layout.projectDirectory.dir("src/main/viaduct/schema"))
            prefixPath.set(
                moduleExt.modulePackageSuffix.map { raw ->
                    val trimmed = raw.trim()
                    (if (trimmed.isEmpty()) "" else trimmed.replace('.', '/')) + "/graphql"
                }
            )
            outputDirectory.set(schemaPartitionDirectory())
        }
    }

    private fun Project.setupOutgoingConfigurationForPartitionSchema(assembleSchemaPartitionTask: TaskProvider<AssembleSchemaPartitionTask>) {
        val schemaPartitionCfg =
            configurations.create(ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING).apply {
                description = "Consumable configuration containing the module's schema partition (aka, 'local schema')."
                isCanBeConsumed = true
                isCanBeResolved = false
                attributes {
                    attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION)
                }
            }
        schemaPartitionCfg.outgoing.artifact(assembleSchemaPartitionTask.flatMap { it.outputDirectory })
    }

    private fun Project.setupIncomingConfigurationForCentralSchema(): Configuration {
        val centralSchemaIncomingCfg =
            configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING).apply {
                description = "Resolvable configuration for the central schema (used to generate resolver base classes)."
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA)
                }
            }
        return centralSchemaIncomingCfg
    }

    private fun Project.setupGenerateResolverBasesTask(
        moduleExt: ViaductModuleExtension,
        centralSchemaIncomingCfg: Configuration
    ): TaskProvider<GenerateResolverBasesTask> {
        val appExt = rootProject.extensions.getByType(ViaductApplicationExtension::class.java)

        return tasks.register<GenerateResolverBasesTask>("generateViaductResolverBases") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            centralSchemaFiles.from(
                centralSchemaIncomingCfg.incoming.artifactView {}.files.asFileTree.matching { include("**/*.graphqls") }
            )
            wireToExtensions(moduleExt, appExt)
            tenantFromSourceRegex.set("$centralSchemaDirectoryName/partition/(.*)/graphql")
            classpath.setFrom(files(ViaductPluginCommon.getClassPathElements(this@ViaductModulePlugin::class.java)))
            mainClass.set(RESOLVER_CODEGEN_MAIN_CLASS)
        }
    }

    private fun Project.enforceNoDirectModuleDeps() {
        configurations.configureEach {
            withDependencies {
                filterIsInstance<ProjectDependency>().forEach { pd ->
                    // Find project by name - works for both flat and nested structures
                    // since rootProject.allprojects includes all subprojects at any depth
                    val target = rootProject.allprojects.find { it.name == pd.name }
                    if (target != null &&
                        target.plugins.hasPlugin(ViaductModulePlugin::class.java) &&
                        this@enforceNoDirectModuleDeps != rootProject &&
                        target != rootProject
                    ) {
                        val from = this@enforceNoDirectModuleDeps.prettyPath()
                        val to = target.prettyPath()
                        val build = this@enforceNoDirectModuleDeps.buildFile

                        throw GradleException(
                            "Module $from must not depend directly on $to; " +
                                "used in $build, use the central schema for inter-module references."
                        )
                    }
                }
            }
        }
    }
}

private fun Project.prettyPath(): String = if (path == ":") ": (root)" else path
