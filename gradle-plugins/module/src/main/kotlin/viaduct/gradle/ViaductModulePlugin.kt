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
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import schemaPartitionDirectory
import viaduct.gradle.ViaductPluginCommon.APPLICATION_PLUGIN_IDS
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.findContainingViaductApplicationProject
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.prettyPath
import viaduct.gradle.ViaductPluginCommon.requireContainingViaductApplicationProject
import viaduct.gradle.task.AssembleSchemaPartitionTask
import viaduct.gradle.task.AssembleTenantModuleConfigFileTask
import viaduct.gradle.task.GenerateResolverBasesTask

open class ViaductModuleExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name suffix for this module (can be empty). */
    val modulePackageSuffix = objects.property(String::class.java)
}

class ViaductModulePlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            val moduleExt = extensions.findByType(ViaductModuleExtension::class.java)
                ?: extensions.create("viaductModule", ViaductModuleExtension::class.java, objects)

            ViaductModulePluginSupport.configureDirectModuleDependencyChecks(this)
            ViaductModulePluginSupport.configureModulePackageSuffixConvention(this, moduleExt)

            val grtIncomingCfg = ViaductModulePluginSupport.createGRTIncomingConfiguration(
                this,
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_INCOMING,
                ViaductPluginCommon.Kind.KOTLIN_GRT_CLASSES,
            )

            val assembleSchemaPartitionTask =
                ViaductModulePluginSupport.setupAssembleSchemaPartitionTask(this, moduleExt)
            ViaductModulePluginSupport.setupOutgoingConfigurationForPartitionSchema(this, assembleSchemaPartitionTask)

            val centralSchemaIncomingCfg = ViaductModulePluginSupport.setupIncomingConfigurationForCentralSchema(this)
            val generateResolverBasesTask = setupGenerateResolverBasesTask(moduleExt, centralSchemaIncomingCfg)

            setupKspRegistryExtractor(moduleExt)

            ViaductModulePluginSupport.wireToContainingApplicationProject(
                this,
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_INCOMING,
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_OUTGOING,
            )

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
            }

            configureIdeaIntegration(generateResolverBasesTask)

            // Convenience task for module-level codegen
            tasks.register("viaductCodegen") {
                group = "viaduct"
                description = "Run Viaduct code generation for this module (GRTs + resolver bases)"

                dependsOn(generateResolverBasesTask)
            }
        }

    private fun Project.setupKspRegistryExtractor(moduleExt: ViaductModuleExtension) {
        val version = pluginVersion(ViaductModulePlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)

        // React when KSP is applied by the consumer — add the registry-extractor
        // processor and wire up the assembly task.
        pluginManager.withPlugin("com.google.devtools.ksp") {
            dependencies.add("ksp", "com.airbnb.viaduct:buildtime:$version")

            // Bridge task: copies KSP's descriptor output to a stable intermediates directory.
            //
            // Two reasons for this indirection:
            // 1. KSP registers kspKotlin lazily, so the assembly task can't take a typed
            //    TaskProvider dependency on it; the string "dependsOn" below is the only option.
            //    The Sync wraps that into a typed provider that assembleTask can depend on safely.
            // 2. Gradle's Sync tracks its OutputDirectory, so when KSP removes a descriptor
            //    (because the upstream source file was deleted), the Sync re-runs and the
            //    deleted descriptor is not copied — propagating the deletion into intermediates.
            //
            // See impldocs/execution-registry-ksp-pipeline.md for the full pipeline explanation.
            val extractKspDescriptors = tasks.register<Sync>(
                "extractKspRegistryDescriptors"
            ) {
                from(layout.buildDirectory.dir("generated/ksp/main/resources/viaduct-registry"))
                into(layout.buildDirectory.dir("intermediates/viaduct-registry-descriptors"))
                dependsOn("kspKotlin")
            }

            val assembleTask = tasks.register<AssembleTenantModuleConfigFileTask>(
                "assembleViaductModuleConfigFile"
            ) {
                group = "viaduct"
                description = "Assembles tenant module config from KSP descriptors"

                descriptorDir.set(layout.buildDirectory.dir("intermediates/viaduct-registry-descriptors"))
                executorFactory.set(AssembleTenantModuleConfigFileTask.EXECUTOR_FACTORY)
                this.codegenClasspath.from(codegenClasspath)
                outputDir.set(
                    project.layout.buildDirectory.dir("generated-resources/viaduct-registry")
                )

                dependsOn(extractKspDescriptors)
            }

            // Wire assembly output into main resources so it lands in the module's JAR
            pluginManager.withPlugin("java") {
                project.extensions
                    .getByType(JavaPluginExtension::class.java)
                    .sourceSets.named("main").configure {
                        resources.srcDir(assembleTask.flatMap { it.outputDir })
                    }
            }

            // Wire tenant package (needs afterEvaluate to read appExt)
            afterEvaluate {
                val appExt = findContainingViaductApplicationProject()
                    ?.extensions
                    ?.findByType(ViaductApplicationExtension::class.java)
                if (appExt != null) {
                    val pkg = computeTenantPackage(moduleExt, appExt)
                    assembleTask.configure {
                        tenantPackage.set(pkg)
                        tenantPackagePrefix.set(appExt.modulePackagePrefix)
                    }
                }
                validateKspConfiguration()
            }
        }

        afterEvaluate {
            if (!plugins.hasPlugin("com.google.devtools.ksp")) {
                throw GradleException(
                    "Viaduct module '${project.displayName}' requires the KSP plugin but it is not applied.\n" +
                        "Add 'com.google.devtools.ksp' to your plugins block:\n" +
                        "  plugins {\n" +
                        "    id(\"com.google.devtools.ksp\") version \"<kotlin-version>-<ksp-version>\"\n" +
                        "  }\n" +
                        "See the Viaduct documentation for supported Kotlin and KSP versions."
                )
            }
        }
    }

    /**
     * We intentionally validate only the Kotlin plugin version that Viaduct supports.
     *
     * We can reliably detect whether `com.google.devtools.ksp` is applied, but Gradle's public
     * plugin APIs do not expose the resolved version of an applied plugin.
     *
     * We intentionally do not duplicate Kotlin/KSP's own compatibility checks here. They already
     * detect version mismatches and report them clearly, so this plugin only enforces Viaduct's
     * supported Kotlin range.
     */
    private fun Project.validateKspConfiguration() {
        val kotlinVersion = getKotlinPluginVersion()
        val error = validateKotlinVersion(kotlinVersion)
        if (error != null) throw GradleException(error)
    }

    internal companion object {
        private const val RESOLVER_CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.ViaductGenerator\$Main"

        fun validateKotlinVersion(kotlinVersion: String): String? {
            val major = kotlinVersion.substringBefore('.').toIntOrNull() ?: return null
            val minor = kotlinVersion.substringAfter('.').substringBefore('.').toIntOrNull() ?: return null

            return if (major < 1 || (major == 1 && minor < 9) || major > 2 || (major == 2 && minor > 2)) {
                "Viaduct requires Kotlin version in the range [1.9, 2.2] for KSP1 support. " +
                    "Found: $kotlinVersion. " +
                    "Kotlin 2.3+ requires KSP2 which is not yet supported by Viaduct."
            } else {
                null
            }
        }
    }

    private fun Project.getKotlinPluginVersion(): String {
        val kotlinExt = extensions.findByType(KotlinJvmProjectExtension::class.java)
            ?: throw GradleException("Kotlin JVM plugin must be applied before the Viaduct module plugin.")
        return kotlinExt.coreLibrariesVersion
    }

    internal fun computeTenantPackage(
        moduleExt: ViaductModuleExtension,
        appExt: ViaductApplicationExtension
    ): String {
        val prefix = appExt.modulePackagePrefix.get()
        val suffix = moduleExt.modulePackageSuffix.getOrElse("")
        return if (suffix.isBlank()) prefix else "$prefix.$suffix"
    }

    private fun Project.setupGenerateResolverBasesTask(
        moduleExt: ViaductModuleExtension,
        centralSchemaIncomingCfg: Configuration
    ): TaskProvider<GenerateResolverBasesTask> {
        val version = pluginVersion(ViaductModulePlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)
        val taskProvider = tasks.register<GenerateResolverBasesTask>("generateViaductResolverBases") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            centralSchemaFiles.from(
                centralSchemaIncomingCfg.incoming.artifactView {}.files.asFileTree.matching { include("**/*.graphqls") }
            )
            tenantFromSourceRegex.set("$centralSchemaDirectoryName/partition/(.*)/graphql")
            classpath.setFrom(codegenClasspath)
            mainClass.set(RESOLVER_CODEGEN_MAIN_CLASS)
        }

        // We intentionally validate here so same-project builds can finish configuring
        // viaductApplication { ... } before we read modulePackagePrefix, while still
        // failing during configuration (e.g. on `help`) instead of waiting for task execution.
        //
        // Keep this block narrow and "safe":
        // - OK: plugin presence checks, reading final extension values, configuring already-registered tasks
        // - NOT OK: filesystem probing, task registration, dependency resolution, task-graph inspection,
        //           or any other late configuration unrelated to extension validation
        ViaductModulePluginSupport.validateContainingApplicationProject(
            this,
            "com.airbnb.viaduct.module-gradle-plugin",
        ) { appExt ->
            taskProvider.configure { wireToExtensions(moduleExt, appExt) }
        }

        return taskProvider
    }
}

object ViaductModulePluginSupport {
    private val MODULE_PLUGIN_IDS = listOf(
        "com.airbnb.viaduct.module-gradle-plugin",
        "com.airbnb.viaduct.module-java-gradle-plugin",
    )

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
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, kind)
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
            }
        }

    fun setupAssembleSchemaPartitionTask(
        project: Project,
        moduleExt: ViaductModuleExtension,
    ): TaskProvider<AssembleSchemaPartitionTask> =
        project.tasks.register<AssembleSchemaPartitionTask>("prepareViaductSchemaPartition") {
            val schemaDir = project.layout.projectDirectory.dir("src/main/viaduct/schema")
            graphqlSrcDir.set(schemaDir)
            schemaFiles.setFrom(project.fileTree(schemaDir).matching { include("**/*.graphqls") })
            prefixPath.set(
                moduleExt.modulePackageSuffix.map { raw ->
                    val trimmed = raw.trim()
                    (if (trimmed.isEmpty()) "" else trimmed.replace('.', '/')) + "/graphql"
                },
            )
            outputDirectory.set(project.schemaPartitionDirectory())
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
                attributes {
                    attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION)
                }
            }
        schemaPartitionCfg.outgoing.artifact(assembleSchemaPartitionTask.flatMap { it.outputDirectory })
    }

    fun setupIncomingConfigurationForCentralSchema(project: Project): Configuration =
        project.configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING).apply {
            description = "Resolvable configuration for the central schema (used to generate resolver base classes)."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA)
            }
        }

    fun validateContainingApplicationProject(
        project: Project,
        modulePluginId: String,
        configure: Project.(ViaductApplicationExtension) -> Unit,
    ) {
        project.afterEvaluate {
            val applicationProject = requireContainingViaductApplicationProject(modulePluginId)
            val appExt = applicationProject.extensions.getByType(ViaductApplicationExtension::class.java)
            val prefix = appExt.modulePackagePrefix.orNull
            if (prefix.isNullOrBlank()) {
                throw GradleException(
                    "viaductApplication.modulePackagePrefix must be set in the containing Viaduct " +
                        "application project ${applicationProject.prettyPath()}. " +
                        "Add it to that build file:\n" +
                        "  viaductApplication {\n" +
                        "    modulePackagePrefix = \"com.example.myapp\"\n" +
                        "  }",
                )
            }
            project.configure(appExt)
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
        configurations.configureEach {
            withDependencies {
                filterIsInstance<ProjectDependency>().forEach { pd ->
                    val target = this@enforceNoDirectModuleDeps.findProject(pd.path)
                    val applicationProject = this@enforceNoDirectModuleDeps.findContainingViaductApplicationProject()
                    if (target != null &&
                        isViaductModule(target) &&
                        this@enforceNoDirectModuleDeps != applicationProject &&
                        target != applicationProject
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

    private fun isViaductModule(target: Project): Boolean = MODULE_PLUGIN_IDS.any { pluginId -> target.plugins.hasPlugin(pluginId) }
}
