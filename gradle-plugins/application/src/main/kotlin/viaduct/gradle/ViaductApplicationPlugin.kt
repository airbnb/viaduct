package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import javaGrtClassesDirectory
import javaGrtSourcesDirectory
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.register
import viaduct.apiannotations.ExperimentalApi
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaGRTCompileClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.validateApplicationProjectPlacement
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.GenerateGRTClassFilesTask
import viaduct.gradle.task.GenerateJavaGRTSourcesTask
import viaduct.service.api.scoping.SchemaScopingValidator

@OptIn(ExperimentalApi::class)
abstract class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            validateApplicationProjectPlacement()

            val appExtension =
                extensions.create("viaductApplication", ViaductApplicationExtension::class.java, objects)
            validateSchemaScopingAfterEvaluate(appExtension)

            val assembleCentralSchemaTask = setupAssembleCentralSchemaTask()
            setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask)

            val kotlinGRTJar = setupKotlinGenerateGRTsTask(assembleCentralSchemaTask)
            val javaGRTJar = setupJavaGenerateGRTsTask(assembleCentralSchemaTask)

            configureIdeaIntegration(kotlinGRTJar)

            setupConsumableConfigurationForGRT(
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_OUTGOING,
                ViaductPluginCommon.Kind.KOTLIN_GRT_CLASSES,
                kotlinGRTJar.flatMap { it.archiveFile },
            )
            setupConsumableConfigurationForGRT(
                ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_OUTGOING,
                ViaductPluginCommon.Kind.JAVA_GRT_CLASSES,
                javaGRTJar.flatMap { it.archiveFile },
            )

            // Expose the Kotlin GRT jar on the application project's own classpath so local sources
            // (main + tests) can reference generated types. Java-specific application projects can
            // depend on generateViaductJavaGRTs explicitly.
            this.dependencies.add("api", files(kotlinGRTJar.flatMap { it.archiveFile }))
        }

    /**
     * Runs the cross-property scoping validator once the build's `viaductApplication` block has
     * settled. Per-ID syntax is enforced at setter time inside [ViaductApplicationExtension]; the
     * subset and universe-presence invariants span both declarations, so they fire here — after
     * `afterEvaluate` — to stay independent of declaration order and to batch every violation into
     * one failure.
     *
     * Configuration-cache safe: the hook captures only the extension (not the `Project`) and reads
     * `schemaScoping` during the configuration phase, before any task graph is serialized.
     */
    private fun Project.validateSchemaScopingAfterEvaluate(appExtension: ViaductApplicationExtension) {
        afterEvaluate {
            val errors = SchemaScopingValidator.validate(appExtension.schemaScoping.get())
            if (errors.isNotEmpty()) {
                throw GradleException(
                    "viaductApplication scope configuration is invalid:\n" +
                        errors.joinToString("\n") { "  [${it.code}] ${it.message}" },
                )
            }
        }
    }

    private fun Project.setupAssembleCentralSchemaTask(): TaskProvider<AssembleCentralSchemaTask> {
        val allPartitions = configurations.create(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING).apply {
            description = "Resolvable configuration where all viaduct-module plugins send their schema partitions."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION) }
        }

        val assembleCentralSchemaTask = tasks.register<AssembleCentralSchemaTask>("assembleViaductCentralSchema") {
            schemaPartitions.setFrom(allPartitions.incoming.artifactView {}.files)

            baseSchemaFiles.setFrom(
                project.fileTree("src/main/viaduct/schemabase") {
                    include("**/*.graphqls")
                }
            )

            // Root types schema files: global Query/Mutation/Subscription extensions
            // for the entire project (not module-specific)
            commonSchemaFiles.setFrom(
                project.fileTree("src/viaduct/schema") {
                    include("**/*.graphqls")
                }
            )

            outputDirectory.set(centralSchemaDirectory())
        }

        return assembleCentralSchemaTask
    }

    /** Call the bytecode-generator to generate Kotlin GRT files. */
    private fun Project.setupKotlinGenerateGRTsTask(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>): TaskProvider<Jar> {
        val version = pluginVersion(ViaductApplicationPlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)

        val generateGRTClassesTask = tasks.register<GenerateGRTClassFilesTask>("generateViaductGRTClassFiles") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            grtClassesDirectory.set(grtClassesDirectory())
            schemaFiles.setFrom(assembleCentralSchemaTask.flatMap { it.outputDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") }.files } })
            classpath.setFrom(codegenClasspath)
            mainClass.set(CODEGEN_MAIN_CLASS)
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductGRTs") {
            group = "viaduct"
            description = "Package GRT class files with the central schema."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            from(generateGRTClassesTask.flatMap { it.grtClassesDirectory })

            from(assembleCentralSchemaTask.flatMap { it.outputDirectory }) {
                into("viaduct/centralSchema")
                exclude(BUILTIN_SCHEMA_FILE)
                includeEmptyDirs = false
            }
        }

        return generateGRTsTask
    }

    /**
     * Generate Java GRT source files from the central schema, compile them with javac,
     * and package the resulting classes with the central schema into a Jar.
     */
    private fun Project.setupJavaGenerateGRTsTask(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>): TaskProvider<Jar> {
        val version = pluginVersion(ViaductApplicationPlugin::class.java)
        val codegenClasspath = createOrGetJavaCodegenClasspath(version)
        val grtCompileClasspath = createOrGetJavaGRTCompileClasspath(version)

        val generateGRTSourcesTask = tasks.register<GenerateJavaGRTSourcesTask>("generateViaductJavaGRTSources") {
            grtSourcesDirectory.set(javaGrtSourcesDirectory())
            schemaFiles.setFrom(
                assembleCentralSchemaTask.flatMap { it.outputDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") }.files } }
            )
            classpath.setFrom(codegenClasspath)
        }

        val compileGRTJavaTask = tasks.register<JavaCompile>("compileViaductJavaGRTJava") {
            dependsOn(generateGRTSourcesTask)
            source = fileTree(generateGRTSourcesTask.flatMap { it.grtSourcesDirectory })
            destinationDirectory.set(javaGrtClassesDirectory())
            classpath = grtCompileClasspath
            options.isIncremental = true
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductJavaGRTs") {
            group = "viaduct"
            description = "Package Java GRT class files (without central schema — already bundled in Kotlin GRT jar)."

            archiveBaseName.set("viaduct-java-grt")
            includeEmptyDirs = false

            from(compileGRTJavaTask.map { it.destinationDirectory })
        }

        return generateGRTsTask
    }

    private fun Project.setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>) {
        configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING).apply {
            description = """
              Consumable configuration consisting of a directory containing all schema fragments.  This directory
              is organized as a top-level file named $BUILTIN_SCHEMA_FILE, plus directories named "parition[/module-name]/graphql",
              where module-name is the modulePackageSuffix of the module with dots replaced by slashes (this segment is
              not present if the suffix is blank).
            """.trimIndent()
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA) }
            outgoing.artifact(assembleCentralSchemaTask)
        }
    }

    private fun Project.setupConsumableConfigurationForGRT(
        configName: String,
        kind: String,
        artifact: Provider<RegularFile>
    ) {
        configurations.create(configName).apply {
            description =
                "Consumable configuration for the jar file containing the GRT classes plus the central schema's graphqls file."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, kind)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR)
                )
            }
            outgoing.artifact(artifact)
        }
    }

    companion object {
        private const val CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
        const val BUILTIN_SCHEMA_FILE = "BUILTIN_SCHEMA.graphqls"
    }
}
