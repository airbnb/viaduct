package viaduct.gradle.task

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import resolverBasesDirectory
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductModulePackageLayout
import viaduct.gradle.ViaductPluginCommon
import viaduct.gradle.runCodegen
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.toBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

@InternalApi
@CacheableTask
abstract class GenerateResolverBasesTask
    @Inject
    constructor(
        private val workerExecutor: WorkerExecutor
    ) : DefaultTask() {
        init {
            description = "Generate abstract Kotlin resolver base classes from the merged schema. Implement these classes to write your GraphQL field resolvers."
        }

        @get:Input
        abstract val mainClass: Property<String>

        @get:Input
        abstract val buildFlags: MapProperty<String, String>

        @get:Classpath
        abstract val classpath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val centralSchemaFiles: ConfigurableFileCollection

        @get:Input
        abstract val tenantPackagePrefix: Property<String>

        @get:Input
        abstract val tenantPackage: Property<String>

        @get:Input
        abstract val tenantFromSourceRegex: Property<String>

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            val flagFile = temporaryDir.resolve("viaduct_build_flags")
            flagFile.writeText(ViaductPluginCommon.buildFlagFileContent(buildFlags.get()))

            val binarySchemaFile = temporaryDir.resolve("schema.bgql")
            ViaductSchema.fromGraphQLSchema(centralSchemaFiles.files.toList())
                .toBinaryFile(binarySchemaFile)

            workerExecutor.runCodegen(
                classpath,
                mainClass.get(),
                listOf(
                    "--schema_files",
                    centralSchemaFiles.files.map { it.absolutePath }.sorted().joinToString(","),
                    "--binary_schema_file",
                    binarySchemaFile.absolutePath,
                    "--tenant_package_prefix",
                    tenantPackagePrefix.get(),
                    "--flag_file",
                    flagFile.absolutePath,
                    "--tenant_pkg",
                    tenantPackage.get(),
                    "--resolver_generated_directory",
                    outputDirectory.get().asFile.absolutePath,
                    "--tenant_from_source_name_regex",
                    tenantFromSourceRegex.get()
                )
            )
        }

        fun Project.wireToModuleLayout(moduleLayout: ViaductModulePackageLayout) {
            tenantPackagePrefix.set(moduleLayout.resolverBasePackagePrefix)
            tenantPackage.set(moduleLayout.resolverBasePackage)
            outputDirectory.set(
                resolverBasesDirectory().map { base ->
                    base.dir(moduleLayout.fullTenantPackagePath)
                }
            )
        }
    }
