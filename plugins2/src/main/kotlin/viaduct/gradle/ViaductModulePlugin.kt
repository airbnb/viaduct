package viaduct.gradle

import centralSchemaDirectoryName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import resolverBasesDirectory
import schemaPartitionDirectory

open class ViaductModuleExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name suffix for this module (may be empty). */
    val modulePackageSuffix = objects.property(String::class.java)
}

class ViaductModulePlugin : Plugin<Project> {
    companion object {
        private val RESOLVER_CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.ViaductGenerator\$Main"
    }

    override fun apply(project: Project): Unit = with(project) {
        // Create module extension
        val moduleExt = extensions.create("viaductModule", ViaductModuleExtension::class.java, objects)

        // If we've been applied inside the viaduct-application plugin, then we want a default of "",
        // but if it's not in viaduct-application then there is no convention and needs to be set
        // explicitly
        pluginManager.withPlugin("viaduct-application") {
            moduleExt.modulePackageSuffix.convention("")
        }
        // Create Configurations
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
                description = "Resolvable configuration for the central schema (used to generate resolver base classes)."isCanBeConsumed = false
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

                // These will make us more friendly to IDEs and other tools
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR)
                )
            }
        }

        // Create Tasks
        prepareSchemaPartitionTask(moduleExt, schemaPartitionCfg)
        val generateResolverBasesTask = generateResolverBasesTask(moduleExt, centralSchemaIncomingCfg, resolverBasesDirectory())

        // Register Configurations (between this gradle project the viaduct-application project.
        rootProject.pluginManager.withPlugin("viaduct-application") {
            // Register this module’s outgoing schema partition with viaduct-application's ALL_SCHEMA incoming config
            rootProject.dependencies.add(
                ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING,
                rootProject.dependencies.project(
                    mapOf(
                        "path" to project.path,
                        "configuration" to ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING
                    )
                )
            )

            // Register this module's incoming central schema
            dependencies.add(
                ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING,
                project.dependencies.project(
                    mapOf(
                        "path" to rootProject.path,
                        "configuration" to ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING
                    )
                )
            )

            // Register this module's incoming GRT classes
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

        // Wire incoming GRT classes into the various source sets
        plugins.withId("java") {
            configurations.named("implementation").configure { extendsFrom(grtIncomingCfg) }
            configurations.named("testImplementation").configure { extendsFrom(grtIncomingCfg) }
        }
        pluginManager.withPlugin("java-test-fixtures") {
            configurations.named("testFixturesImplementation").configure { extendsFrom(grtIncomingCfg) }
        }

        // Wire generated resolver bases with Kotlin plugin
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val kotlinExt = extensions.getByType(KotlinJvmProjectExtension::class.java)

            kotlinExt.sourceSets.named("main") {
                kotlin.srcDir(generateResolverBasesTask.map { it.outputs.files })
            }
        }
    }

    private fun Project.prepareSchemaPartitionTask(
        moduleExt: ViaductModuleExtension,
        schemaPartitionCfg: Configuration,
    ): TaskProvider<*> {
        val graphqlSrcDir: Directory = layout.projectDirectory.dir("src/main/viaduct/schema")

        // Our codegen tools use the directory path of schema files to determine which module it
        // belongs to.  This imposes a restriction on our build tools include the
        // module name in directory paths.  Our gradle plugin for viaduct moves schema files
        // around, but needs to do so in a way that preserves the module name.  The prefixPath
        // defined here accomplishes this preservation.
        // TODO - find a better way to associate schema files with modules
        val prefixPathProvider = moduleExt.modulePackageSuffix.map { raw ->
            raw.trim().let {
                if (it.isEmpty()) it
                else it.replace('.', '/')
            } + "/graphql"
        }

        // Copy whatever exists into a normalized partition directory, under the computed prefix
        val prepareSchemaPartitionTask = tasks.register<Sync>("prepareViaductSchemaPartition") {
            into(schemaPartitionDirectory()) // Overall destination

            val prefixPath = prefixPathProvider.get()
            from(graphqlSrcDir) {
                include("**/*.graphqls")
                into(prefixPath) // put prefix in front
            }
            includeEmptyDirs = false
        }

        schemaPartitionCfg.outgoing.artifact(schemaPartitionDirectory()) {
            builtBy(prepareSchemaPartitionTask)
        }

        return prepareSchemaPartitionTask
    }

    private fun Project.generateResolverBasesTask(
        moduleExt: ViaductModuleExtension,
        centralSchemaIncomingCfg: Configuration,
        resolverBasesDir: Provider<Directory>,
    ): TaskProvider<JavaExec> {
        // Build a FileCollection from the plugin's classloader URLs (includes plugin impl deps like :tenant:codegen)
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductModulePlugin::class.java))

        val generateResolverBasesTask = tasks.register<JavaExec>("generateViaductResolverBases") {
            group = "viaduct"
            description = "Generate resolver base Kotlin sources from central schema and module partition."

            val csArtifactFiles = centralSchemaIncomingCfg.incoming.artifactView {}.files

            // Up-to-date inputs/outputs
            inputs.files(csArtifactFiles) // TODO - this seems unnecessarily granular
                .withPropertyName("viaductCentralSchemaFiles")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional(false)
            outputs.dir(resolverBasesDir)
                .withPropertyName("viaductResolverBasesDir")

            // Use the plugin's classpath (contains :tenant:codegen and its deps)
            classpath = pluginClasspath
            mainClass.set(RESOLVER_CODEGEN_MAIN_CLASS)

            doFirst {
                val csFiles = csArtifactFiles.asFileTree
                val centralSchemaFilePaths = csFiles.map { it.absolutePath }.sorted().joinToString(",")

                // Get app-wide tenantPackagePrefix from viaduct-application project
                val appExt = rootProject.extensions.getByType(ViaductApplicationExtension::class.java)
                val tenantPackagePrefix = appExt.modulePackagePrefix.get()

                // Handle special case that modulePackageSuffix is ""
                val suffix = moduleExt.modulePackageSuffix.get()
                val blankSuffix = suffix.isBlank()
                val pkgPrefix = if (blankSuffix) "" else tenantPackagePrefix
                val pkg = if (blankSuffix) tenantPackagePrefix else suffix
                val packagePath = (if (blankSuffix) pkg else "$pkgPrefix.$pkg").replace('.', '/')

                val resolverBuildBasePath = resolverBasesDir.get().asFile.toPath()
                val resolverBuildAugmentedDir = resolverBuildBasePath.resolve(packagePath).toFile().apply { mkdirs() }
                val resolverBasesDirPath = resolverBuildAugmentedDir.absolutePath

                args(
                    "--schema_files", centralSchemaFilePaths,
                    "--tenant_package_prefix", pkgPrefix,
                    "--tenant_pkg", pkg,
                    "--resolver_generated_directory", resolverBasesDirPath,
                    "--tenant_from_source_name_regex", "$centralSchemaDirectoryName/partition/(.*)/graphql",
                )
            }
        }

        return generateResolverBasesTask
    }
}
