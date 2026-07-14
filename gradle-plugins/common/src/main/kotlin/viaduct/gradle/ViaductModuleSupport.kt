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
import viaduct.gradle.ViaductPluginCommon.findContainingViaductApplicationProject
import viaduct.gradle.ViaductPluginCommon.hasViaductApplicationPlugin
import viaduct.gradle.ViaductPluginCommon.prettyPath
import viaduct.gradle.ViaductPluginCommon.requireViaductTopology
import viaduct.gradle.task.AssembleSchemaPartitionTask

@StableApi
open class ViaductModuleExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name suffix for this module (can be empty). */
    val modulePackageSuffix = objects.property(String::class.java)
}

@InternalApi
data class ViaductModulePackageLayout(
    val modulePackagePrefix: String,
    val modulePackageSuffix: String,
) {
    val resolverBasePackagePrefix: String = if (modulePackageSuffix.isBlank()) "" else modulePackagePrefix
    val resolverBasePackage: String = if (modulePackageSuffix.isBlank()) modulePackagePrefix else modulePackageSuffix
    val fullTenantPackage: String = if (modulePackageSuffix.isBlank()) modulePackagePrefix else "$modulePackagePrefix.$modulePackageSuffix"
    val schemaPartitionPrefixPath: String =
        if (modulePackageSuffix.isBlank()) {
            "graphql"
        } else {
            "${modulePackageSuffix.replace('.', '/')}/graphql"
        }
    val fullTenantPackagePath: String = fullTenantPackage.replace('.', '/')
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
        return ViaductModulePackageLayout(
            modulePackagePrefix = topology.modulePackagePrefix,
            modulePackageSuffix = suffix,
        )
    }

    fun configureDirectModuleDependencyChecks(project: Project) {
        project.pluginManager.withPlugin("java") { project.enforceNoDirectModuleDeps() }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { project.enforceNoDirectModuleDeps() }
    }

    fun configureModulePackageSuffixConvention(
        project: Project,
        moduleExt: ViaductModuleExtension
    ) {
        APPLICATION_PLUGIN_IDS.forEach { pluginId ->
            project.pluginManager.withPlugin(pluginId) {
                moduleExt.modulePackageSuffix.convention("")
            }
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

    fun setupIncomingConfigurationForCentralSchema(project: Project): Configuration =
        project.configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING).apply {
            description = "Resolvable configuration for the central schema (used to generate resolver base classes)."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes { attrs ->
                attrs.attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA)
            }
        }

    fun validateContainingApplicationProjectPlugin(
        project: Project,
        modulePluginId: String,
    ) {
        project.afterEvaluate {
            val topology = project.requireViaductTopology(modulePluginId)
            val applicationProject = project.findProject(topology.applicationProjectPath)
                ?: throw GradleException(
                    "Viaduct settings topology declares application project " +
                        "'${topology.applicationProjectPath}' for module ${project.prettyPath()}, " +
                        "but that project is not included in this Gradle build.",
                )
            if (!applicationProject.hasViaductApplicationPlugin()) {
                throw GradleException(
                    "Viaduct module ${project.prettyPath()} is declared under application project " +
                        "${applicationProject.prettyPath()}, but that project does not apply one of " +
                        "${APPLICATION_PLUGIN_IDS.joinToString(", ") { "'$it'" }}.",
                )
            }
        }
    }

    fun wireToContainingApplicationProject(
        project: Project,
        grtIncomingConfigName: String,
        grtOutgoingConfigName: String,
    ) {
        var wired = false

        generateSequence(project) { it.parent }.forEach { candidate ->
            APPLICATION_PLUGIN_IDS.forEach { pluginId ->
                candidate.pluginManager.withPlugin(pluginId) {
                    if (wired || project.findContainingViaductApplicationProject() != candidate) return@withPlugin

                    candidate.dependencies.add(
                        ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING,
                        candidate.dependencies.project(
                            mapOf(
                                "path" to project.path,
                                "configuration" to ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING,
                            ),
                        ),
                    )
                    candidate.dependencies.add("runtimeOnly", project)

                    project.dependencies.add(
                        ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING,
                        project.dependencies.project(
                            mapOf(
                                "path" to candidate.path,
                                "configuration" to ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING,
                            ),
                        ),
                    )

                    project.dependencies.add(
                        grtIncomingConfigName,
                        project.dependencies.project(
                            mapOf(
                                "path" to candidate.path,
                                "configuration" to grtOutgoingConfigName,
                            ),
                        ),
                    )

                    wired = true
                }
            }
        }
    }

    private fun Project.enforceNoDirectModuleDeps() {
        configurations.configureEach { configuration ->
            configuration.withDependencies { deps ->
                val topology = this@enforceNoDirectModuleDeps.requireViaductTopology("com.airbnb.viaduct.module-gradle-plugin")
                deps.filterIsInstance<ProjectDependency>().forEach { pd ->
                    val target = this@enforceNoDirectModuleDeps.findProject(pd.path)
                    if (target != null &&
                        topology.isModuleProject(target.path) &&
                        this@enforceNoDirectModuleDeps.path != topology.applicationProjectPath &&
                        target.path != topology.applicationProjectPath
                    ) {
                        val from = this@enforceNoDirectModuleDeps.prettyPath()
                        val to = target.prettyPath()
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
