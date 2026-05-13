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
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import schemaPartitionDirectory
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
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

            pluginManager.withPlugin("java") { enforceNoDirectModuleDeps() }
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { enforceNoDirectModuleDeps() }

            SUPPORTED_APPLICATION_PLUGIN_IDS.forEach { pluginId ->
                pluginManager.withPlugin(pluginId) {
                    moduleExt.modulePackageSuffix.convention("")
                }
            }

            val grtIncomingCfg = configurations.create(ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_INCOMING).apply {
                description = "Resolvable configuration for the GRT jar file."
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.KOTLIN_GRT_CLASSES)
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

            setupKspRegistryExtractor(moduleExt)

            // Register wiring with the root application plugin (Kotlin or Java variant) if present
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
                        ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_INCOMING,
                        project.dependencies.project(
                            mapOf(
                                "path" to rootProject.path,
                                "configuration" to ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_OUTGOING
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

            // Generated resolver bases into Kotlin source set
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                val kotlinExt = extensions.getByType(KotlinJvmProjectExtension::class.java)
                kotlinExt.sourceSets.named("main") {
                    kotlin.srcDir(generateResolverBasesTask.flatMap { it.outputDirectory })
                }

                if (supportsContextReceiversFlag(kotlinExt.coreLibrariesVersion)) {
                    kotlinExt.compilerOptions {
                        freeCompilerArgs.add("-Xcontext-receivers")
                    }
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

    private fun Project.setupKspRegistryExtractor(moduleExt: ViaductModuleExtension) {
        val version = pluginVersion(ViaductModulePlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)

        // React when KSP is applied by the consumer — add the registry-extractor
        // processor and wire up the assembly task.
        pluginManager.withPlugin("com.google.devtools.ksp") {
            dependencies.add("ksp", "com.airbnb.viaduct:buildtime:$version")

            val descriptorRoot = layout.buildDirectory.dir(
                "generated/ksp/main/resources/viaduct-registry"
            )

            val assembleTask = tasks.register<AssembleTenantModuleConfigFileTask>(
                "assembleViaductModuleConfigFile"
            ) {
                group = "viaduct"
                description = "Assembles tenant module config from KSP descriptors"

                descriptorDir.set(descriptorRoot)
                executorFactory.set(AssembleTenantModuleConfigFileTask.EXECUTOR_FACTORY)
                this.codegenClasspath.from(codegenClasspath)
                outputDir.set(
                    project.layout.buildDirectory.dir("generated-resources/viaduct-registry")
                )

                dependsOn("kspKotlin")
            }

            // Wire assembly output into main resources so it lands in the module's JAR
            pluginManager.withPlugin("java") {
                val mainSourceSet = project.extensions
                    .getByType(JavaPluginExtension::class.java)
                    .sourceSets.getByName("main")
                mainSourceSet.resources.srcDir(assembleTask.flatMap { it.outputDir })
            }

            // Wire tenant package (needs afterEvaluate to read appExt)
            afterEvaluate {
                val appExt = rootProject.extensions.findByType(ViaductApplicationExtension::class.java)
                if (appExt != null) {
                    val pkg = computeTenantPackage(moduleExt, appExt)
                    assembleTask.configure { tenantPackage.set(pkg) }
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

        private val SUPPORTED_APPLICATION_PLUGIN_IDS = listOf(
            "com.airbnb.viaduct.application-gradle-plugin",
        )

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

        fun supportsContextReceiversFlag(kotlinVersion: String): Boolean {
            val major = kotlinVersion.substringBefore('.').toIntOrNull() ?: return true
            val minor = kotlinVersion.substringAfter('.').substringBefore('.').toIntOrNull() ?: return true

            return major < 2 || (major == 2 && minor < 2)
        }
    }

    private fun Project.getKotlinPluginVersion(): String {
        val kotlinExt = extensions.findByType(KotlinJvmProjectExtension::class.java)
            ?: throw GradleException("Kotlin JVM plugin must be applied before the Viaduct module plugin.")
        return kotlinExt.coreLibrariesVersion
    }

    private fun computeTenantPackage(
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
        afterEvaluate {
            val appliedAppPluginId = SUPPORTED_APPLICATION_PLUGIN_IDS.firstOrNull {
                rootProject.plugins.hasPlugin(it)
            }
            if (appliedAppPluginId == null) {
                throw GradleException(
                    "Apply one of ${SUPPORTED_APPLICATION_PLUGIN_IDS.joinToString(", ") { "'$it'" }} " +
                        "to the root project before applying 'com.airbnb.viaduct.module-gradle-plugin'."
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
        if (target.plugins.hasPlugin(ViaductModulePlugin::class.java)) return true
        // Detect the sibling Java module plugin by ID to avoid a compile dep on :plugins-module-java.
        return target.plugins.hasPlugin("com.airbnb.viaduct.module-java-gradle-plugin")
    }
}

private fun Project.prettyPath(): String = if (path == ":") ": (root)" else path
