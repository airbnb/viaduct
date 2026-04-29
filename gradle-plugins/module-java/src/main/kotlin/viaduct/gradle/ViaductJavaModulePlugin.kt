package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import schemaPartitionDirectory
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.task.AssembleSchemaPartitionTask
import viaduct.gradle.task.GenerateJavaResolverBasesTask

class ViaductJavaModulePlugin : Plugin<Project> {
    companion object {
        private val SUPPORTED_APPLICATION_PLUGIN_IDS = listOf(
            "com.airbnb.viaduct.application-gradle-plugin",
        )
    }

    override fun apply(project: Project): Unit =
        with(project) {
            val moduleExt = extensions.findByType(ViaductModuleExtension::class.java)
                ?: extensions.create("viaductModule", ViaductModuleExtension::class.java, objects)

            pluginManager.withPlugin("java") { enforceNoDirectModuleDeps() }
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { enforceNoDirectModuleDeps() }

            SUPPORTED_APPLICATION_PLUGIN_IDS.forEach { pluginId ->
                pluginManager.withPlugin(pluginId) {
                    moduleExt.modulePackageSuffix.convention("")
                }
            }

            val grtIncomingCfg = configurations.create(ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_INCOMING).apply {
                description = "Resolvable configuration for the GRT jar file."
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.JAVA_GRT_CLASSES)
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

            // Register wiring with the root application plugin (Kotlin or Java variant)
            SUPPORTED_APPLICATION_PLUGIN_IDS.forEach { pluginId ->
                rootProject.pluginManager.withPlugin(pluginId) {
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
                        ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_INCOMING,
                        project.dependencies.project(
                            mapOf(
                                "path" to rootProject.path,
                                "configuration" to ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_OUTGOING
                            )
                        )
                    )
                }
            }

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
                group = "viaduct"
                description = "Run Viaduct code generation for this module (Java resolver bases)"

                dependsOn(generateResolverBasesTask)
            }
        }

    private fun Project.setupAssembleSchemaPartitionTask(moduleExt: ViaductModuleExtension): TaskProvider<AssembleSchemaPartitionTask> {
        return tasks.register<AssembleSchemaPartitionTask>("prepareViaductSchemaPartition") {
            val schemaDir = layout.projectDirectory.dir("src/main/viaduct/schema")
            graphqlSrcDir.set(schemaDir)
            schemaFiles.setFrom(fileTree(schemaDir).matching { include("**/*.graphqls") })
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
    ): TaskProvider<GenerateJavaResolverBasesTask> {
        val version = pluginVersion(ViaductJavaModulePlugin::class.java)
        val codegenClasspath = createOrGetJavaCodegenClasspath(version)
        val taskProvider = tasks.register<GenerateJavaResolverBasesTask>("generateViaductResolverBases") {
            centralSchemaFiles.from(
                centralSchemaIncomingCfg.incoming.artifactView {}.files.asFileTree.matching { include("**/*.graphqls") }
            )
            classpath.setFrom(codegenClasspath)
        }

        afterEvaluate {
            val appliedAppPluginId = SUPPORTED_APPLICATION_PLUGIN_IDS.firstOrNull {
                rootProject.plugins.hasPlugin(it)
            }
            if (appliedAppPluginId == null) {
                throw GradleException(
                    "Apply one of ${SUPPORTED_APPLICATION_PLUGIN_IDS.joinToString(", ") { "'$it'" }} " +
                        "to the root project before applying 'com.airbnb.viaduct.module-java-gradle-plugin'."
                )
            }
            val appExt = rootProject.extensions.getByType(ViaductApplicationExtension::class.java)
            val prefix = appExt.modulePackagePrefix.orNull
            if (prefix.isNullOrBlank()) {
                throw GradleException(
                    "viaductApplication.modulePackagePrefix must be set in the root project. " +
                        "Add it to your root build file:\n" +
                        "  viaductApplication {\n" +
                        "    modulePackagePrefix = \"com.example.myapp\"\n" +
                        "  }"
                )
            }
            taskProvider.configure { wireToExtensions(moduleExt, appExt) }
        }

        return taskProvider
    }

    private fun Project.enforceNoDirectModuleDeps() {
        configurations.configureEach {
            withDependencies {
                filterIsInstance<ProjectDependency>().forEach { pd ->
                    val target = this@enforceNoDirectModuleDeps.findProject(pd.path)
                    if (target != null &&
                        isViaductModule(target) &&
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

    private fun isViaductModule(target: Project): Boolean {
        if (target.plugins.hasPlugin(ViaductJavaModulePlugin::class.java)) return true
        // Detect the sibling Kotlin module plugin by ID to avoid a compile dep on :plugins-module.
        return target.plugins.hasPlugin("com.airbnb.viaduct.module-gradle-plugin")
    }
}

private fun Project.prettyPath(): String = if (path == ":") ": (root)" else path
