package viaduct.gradle

import centralSchemaDirectoryName
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import resolverBasesDirectory
import schemaPartitionDirectory
import viaduct.gradle.ViaductPluginCommon.addViaductDependencies
import viaduct.gradle.ViaductPluginCommon.applyViaductBOM

open class ViaductModuleExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name suffix for this module (may be empty). */
    val modulePackageSuffix = objects.property(String::class.java)

    /** Version of the Viaduct BOM to use. Defaults to the project version. */
    val bomVersion = objects.property(String::class.java)

    /** Whether to automatically apply the Viaduct BOM platform dependency. Defaults to true. */
    val applyBOM = objects.property(Boolean::class.java).convention(true)

    /** Which Viaduct artifacts to automatically add as dependencies. Defaults to common module ones. */
    val viaductDependencies = objects.setProperty(String::class.java).convention(ViaductPluginCommon.BOM.DEFAULT_MODULE_ARTIFACTS)
}

class ViaductModulePlugin : Plugin<Project> {
    companion object {
        private const val RESOLVER_CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.ViaductGenerator\$Main"
    }

    override fun apply(project: Project): Unit =
        with(project) {
            val moduleExt = extensions.create("viaductModule", ViaductModuleExtension::class.java, objects)

            moduleExt.bomVersion.convention(ViaductPluginCommon.BOM.getDefaultVersion())

            pluginManager.withPlugin("com.airbnb.viaduct.application-gradle-plugin") {
                moduleExt.modulePackageSuffix.convention("")
            }

            plugins.withId("java") {
                if (moduleExt.applyBOM.get()) {
                    applyViaductBOM(moduleExt.bomVersion.get())
                    addViaductDependencies(moduleExt.viaductDependencies.get())
                }
            }

            // Configurations
            val schemaPartitionCfg = configurations.create(ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING).apply {
                description = "Consumable configuration containing the module's schema partition (aka, 'local schema')."
                isCanBeConsumed = true
                isCanBeResolved = false
                attributes {
                    attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION)
                }
            }

            val centralSchemaIncomingCfg =
                configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING).apply {
                    description = "Resolvable configuration for the central schema (used to generate resolver base classes)."
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    attributes {
                        attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA)
                    }
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

            // Tasks
            prepareSchemaPartitionTask(moduleExt, schemaPartitionCfg)
            val generateResolverBasesTask = generateResolverBasesTask(
                moduleExt = moduleExt,
                centralSchemaIncomingCfg = centralSchemaIncomingCfg,
                resolverBasesDir = resolverBasesDirectory()
            )

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
                    kotlin.srcDir(generateResolverBasesTask.map { it.outputs.files })
                }
            }

            // Convenience task for module-level codegen
            tasks.register("viaductCodegen") {
                group = "viaduct"
                description = "Run Viaduct code generation for this module (GRTs + resolver bases)"

                dependsOn(generateResolverBasesTask)

                // Express GRT dependency via configuration files (config-cache safe)
                inputs.files(grtIncomingCfg.incoming.files).withPropertyName("grtClasses").optional(false)
            }
        }

    private fun Project.prepareSchemaPartitionTask(
        moduleExt: ViaductModuleExtension,
        schemaPartitionCfg: Configuration,
    ): TaskProvider<*> {
        val graphqlSrcDir: Directory = layout.projectDirectory.dir("src/main/viaduct/schema")

        val prefixPathProvider = moduleExt.modulePackageSuffix.map { raw ->
            val trimmed = raw.trim()
            (if (trimmed.isEmpty()) "" else trimmed.replace('.', '/')) + "/graphql"
        }

        val prepareSchemaPartitionTask = tasks.register<Sync>("prepareViaductSchemaPartition") {
            // No group: don't want this to appear in task list
            description = "Prepare this module's schema partition."

            into(schemaPartitionDirectory())

            val prefixPath = prefixPathProvider.get()
            from(graphqlSrcDir) {
                include("**/*.graphqls")
                into(prefixPath)
            }
            includeEmptyDirs = false
        }

        schemaPartitionCfg.outgoing.artifact(schemaPartitionDirectory()) {
            builtBy(prepareSchemaPartitionTask)
        }

        return prepareSchemaPartitionTask
    }

    /** Typed, cache-safe args for resolver base generation. */
    abstract class ResolverArgs
        @Inject
        constructor() : CommandLineArgumentProvider {
            @get:InputFiles
            @get:PathSensitive(PathSensitivity.RELATIVE)
            abstract val centralSchemaFiles: ConfigurableFileCollection

            @get:Input
            abstract val tenantPackagePrefix: Property<String>

            @get:Input
            abstract val tenantPkg: Property<String>

            @get:OutputDirectory
            abstract val resolverOutputDir: DirectoryProperty

            @get:Input
            abstract val tenantFromSourceRegex: Property<String>

            override fun asArguments(): Iterable<String> {
                val schemaCsv = centralSchemaFiles.files.map { it.absolutePath }.sorted().joinToString(",")
                return listOf(
                    "--schema_files",
                    schemaCsv,
                    "--tenant_package_prefix",
                    tenantPackagePrefix.get(),
                    "--tenant_pkg",
                    tenantPkg.get(),
                    "--resolver_generated_directory",
                    resolverOutputDir.get().asFile.absolutePath,
                    "--tenant_from_source_name_regex",
                    tenantFromSourceRegex.get()
                )
            }
        }

    private fun Project.generateResolverBasesTask(
        moduleExt: ViaductModuleExtension,
        centralSchemaIncomingCfg: Configuration,
        resolverBasesDir: Provider<Directory>,
    ): TaskProvider<JavaExec> {
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductModulePlugin::class.java))

        val csArtifactDirs = centralSchemaIncomingCfg.incoming.artifactView {}.files
        val centralSchemaFiles = provider {
            csArtifactDirs.asFileTree.matching { include("**/*.graphqls") }
        }

        // Compute pkgPrefix/pkg as Providers (unchanged)
        val appExt = rootProject.extensions.getByType(ViaductApplicationExtension::class.java)
        val suffixProv = moduleExt.modulePackageSuffix
        val blankSuffixProv = suffixProv.map { it.isBlank() }
        val pkgPrefixProv = blankSuffixProv.flatMap { blank ->
            if (blank) objects.property(String::class.java).convention("") else appExt.modulePackagePrefix
        }
        val pkgProv = blankSuffixProv.flatMap { blank ->
            if (blank) appExt.modulePackagePrefix else suffixProv
        }

        val outputAugmentedDir = resolverBasesDir.flatMap { base ->
            val packagePathProv = pkgPrefixProv.flatMap { pfx ->
                pkgProv.map { pkg ->
                    (if (pkg.isBlank()) pfx else "$pfx.$pkg").trim('.').replace('.', '/')
                }
            }
            packagePathProv.map { rel ->
                base.asFile.toPath().resolve(rel).toFile().apply { mkdirs() }
            }.map { dir -> objects.directoryProperty().apply { set(dir) }.get() }
        }

        return tasks.register<JavaExec>("generateViaductResolverBases") {
            group = "viaduct"
            description = "Generate resolver base Kotlin sources from central schema and module partition."

            // Inputs/outputs
            inputs.files(centralSchemaFiles)
                .withPropertyName("viaductCentralSchemaFiles")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional(false)
            outputs.dir(outputAugmentedDir).withPropertyName("viaductResolverBasesDir")

            classpath = pluginClasspath
            mainClass.set(RESOLVER_CODEGEN_MAIN_CLASS)

            argumentProviders.add(
                objects.newInstance(ResolverArgs::class.java).also { args ->
                    args.centralSchemaFiles.from(centralSchemaFiles) // args.<property>.from(...)
                    args.tenantPackagePrefix.set(pkgPrefixProv)
                    args.tenantPkg.set(pkgProv)
                    args.resolverOutputDir.set(outputAugmentedDir)
                    args.tenantFromSourceRegex.set("$centralSchemaDirectoryName/partition/(.*)/graphql")
                }
            )
        }
    }
}
