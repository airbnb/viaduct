package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.TaskProvider
import schemaPartitionDirectory
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi
import viaduct.gradle.ViaductPluginCommon.APPLICATION_PLUGIN_IDS
import viaduct.gradle.ViaductPluginCommon.prettyPath
import viaduct.gradle.ViaductPluginCommon.requireViaductTopology
import viaduct.gradle.ViaductPluginCommon.requireViaductTopologyModuleProjectPaths
import viaduct.gradle.task.AssembleSchemaPartitionTask

@StableApi
interface ViaductModulePackageLayout {
    val modulePackagePrefix: String
    val modulePackageSuffix: String
    val resolverBasePackagePrefix: String
    val resolverBasePackage: String
    val fullTenantPackage: String
    val schemaPartitionPrefixPath: String
    val fullTenantPackagePath: String
}

internal data class DefaultViaductModulePackageLayout(
    override val modulePackagePrefix: String,
    override val modulePackageSuffix: String,
) : ViaductModulePackageLayout {
    override val resolverBasePackagePrefix: String = if (modulePackageSuffix.isBlank()) "" else modulePackagePrefix
    override val resolverBasePackage: String = if (modulePackageSuffix.isBlank()) modulePackagePrefix else modulePackageSuffix
    override val fullTenantPackage: String = if (modulePackageSuffix.isBlank()) modulePackagePrefix else "$modulePackagePrefix.$modulePackageSuffix"
    override val schemaPartitionPrefixPath: String =
        if (modulePackageSuffix.isBlank()) {
            "graphql"
        } else {
            "${modulePackageSuffix.replace('.', '/')}/graphql"
        }
    override val fullTenantPackagePath: String = fullTenantPackage.replace('.', '/')
}

@InternalApi
object ViaductModulePluginSupport {
    fun modulePackageLayout(
        project: Project,
        topology: ViaductApplicationTopology,
    ): ViaductModulePackageLayout {
        val suffix = topology.modulePackageSuffixes[project.path]
            ?: throw GradleException(
                "Project ${project.prettyPath()} is declared as a Viaduct module, but no " +
                    "modulePackageSuffix is present for it in the Viaduct settings topology.",
            )
        return DefaultViaductModulePackageLayout(
            modulePackagePrefix = topology.modulePackagePrefix,
            modulePackageSuffix = suffix,
        )
    }

    fun configureDirectModuleDependencyChecks(project: Project) {
        val topology = project.requireViaductTopology("com.airbnb.viaduct.module-gradle-plugin")
        configureDirectModuleDependencyChecks(project, topology)
    }

    fun configureDirectModuleDependencyChecks(
        project: Project,
        topology: ViaductApplicationTopology,
    ) {
        val applicationProjectPath = topology.applicationProjectPath
        val moduleProjectPaths = project.requireViaductTopologyModuleProjectPaths()

        project.pluginManager.withPlugin("java") {
            project.enforceNoDirectModuleDeps(applicationProjectPath, moduleProjectPaths)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.enforceNoDirectModuleDeps(applicationProjectPath, moduleProjectPaths)
        }
    }

    fun createGRTIncomingConfiguration(
        project: Project,
        configurationName: String,
        kind: String,
    ): Configuration =
        project.configurations.create(configurationName).apply {
            description = "Resolvable configuration for the GRT jar file."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes { attrs ->
                attrs.attribute(ViaductPluginCommon.VIADUCT_KIND, kind)
                attrs.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
                attrs.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
            }
        }

    fun setupViaductApplicationConfiguration(project: Project): Configuration {
        val existing = project.configurations.findByName(ViaductPluginCommon.Configs.VIADUCT_APPLICATION)
        if (existing != null) return existing

        return project.configurations.create(ViaductPluginCommon.Configs.VIADUCT_APPLICATION).apply {
            description = "Dependency bucket for the Viaduct application project that owns this module."
            isCanBeConsumed = false
            isCanBeResolved = false
        }
    }

    fun setupAssembleSchemaPartitionTask(
        project: Project,
        moduleLayout: ViaductModulePackageLayout,
    ): TaskProvider<AssembleSchemaPartitionTask> =
        project.tasks.register("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java) { task ->
            val schemaDir = project.layout.projectDirectory.dir("src/main/viaduct/schema")
            task.graphqlSrcDir.set(schemaDir)
            task.schemaFiles.setFrom(project.fileTree(schemaDir).matching { it.include("**/*.graphqls") })
            task.prefixPath.set(moduleLayout.schemaPartitionPrefixPath)
            task.outputDirectory.set(project.schemaPartitionDirectory())
        }

    fun setupOutgoingConfigurationForPartitionSchema(
        project: Project,
        assembleSchemaPartitionTask: TaskProvider<AssembleSchemaPartitionTask>,
    ) {
        val schemaPartitionCfg =
            project.configurations.create(ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING).apply {
                description = "Consumable configuration containing the module's schema partition (aka, 'local schema')."
                isCanBeConsumed = true
                isCanBeResolved = false
                attributes { attrs ->
                    attrs.attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION)
                }
            }
        schemaPartitionCfg.outgoing.artifact(assembleSchemaPartitionTask.flatMap { it.outputDirectory })
    }

    fun setupIncomingConfigurationForCentralSchema(
        project: Project,
        viaductApplication: Configuration,
    ): Configuration =
        project.configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING).apply {
            description = "Resolvable configuration for the central schema (used to generate resolver base classes)."
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(viaductApplication)
            attributes { attrs ->
                attrs.attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA)
            }
        }

    fun wireCentralSchemaToTopologyApplicationProject(
        project: Project,
        topology: ViaductApplicationTopology,
        viaductApplication: Configuration,
        centralSchemaIncomingCfg: Configuration,
    ) {
        if (topology.applicationProjectPath == project.path) {
            APPLICATION_PLUGIN_IDS.forEach { pluginId ->
                project.pluginManager.withPlugin(pluginId) {
                    val outputs = project.extensions.getByType(ViaductApplicationOutputProviders::class.java)
                    project.dependencies.add(
                        centralSchemaIncomingCfg.name,
                        project.files(outputs.centralSchemaDirectory),
                    )
                }
            }
            return
        }

        project.dependencies.add(
            viaductApplication.name,
            project.dependencies.project(
                mapOf(
                    "path" to topology.applicationProjectPath,
                ),
            ),
        )
    }

    private fun Project.enforceNoDirectModuleDeps(
        applicationProjectPath: String,
        moduleProjectPaths: Set<String>,
    ) {
        configurations.configureEach { configuration ->
            configuration.withDependencies { deps ->
                val projectDependencyPaths = deps.filterIsInstance<ProjectDependency>().map { it.path }
                projectDependencyPaths.forEach { dependencyPath ->
                    if (
                        moduleProjectPaths.contains(dependencyPath) &&
                        this@enforceNoDirectModuleDeps.path != applicationProjectPath &&
                        dependencyPath != applicationProjectPath
                    ) {
                        val from = this@enforceNoDirectModuleDeps.prettyPath()
                        val to = if (dependencyPath == ":") ": (root)" else dependencyPath
                        val build = this@enforceNoDirectModuleDeps.buildFile

                        throw GradleException(
                            "Module $from must not depend directly on $to; " +
                                "used in $build, use the central schema for inter-module references.",
                        )
                    }
                }
            }
        }
    }
}
