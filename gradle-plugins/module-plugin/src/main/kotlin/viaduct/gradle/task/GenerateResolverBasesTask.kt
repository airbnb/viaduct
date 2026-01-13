package viaduct.gradle.task

import java.io.FileOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import resolverBasesDirectory
import viaduct.gradle.ViaductApplicationExtension
import viaduct.gradle.ViaductModuleExtension
import viaduct.gradle.ViaductPluginCommon
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.graphqljava.GJSchema

@CacheableTask
abstract class GenerateResolverBasesTask
    @Inject
    constructor(
        private var execOperations: ExecOperations
    ) : DefaultTask() {
        init {
            group = "viaduct"
            description = "Generate resolver base Kotlin sources from central schema and module partition."
        }

        @get:Input
        abstract val mainClass: Property<String>

        @get:Input
        abstract val buildFlags: MapProperty<String, String>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
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
            writeBSchema(
                GJSchema.fromFiles(centralSchemaFiles.files.toList()),
                FileOutputStream(binarySchemaFile)
            )

            execOperations.javaexec {
                classpath = this@GenerateResolverBasesTask.classpath
                mainClass.set(this@GenerateResolverBasesTask.mainClass.get())
                argumentProviders.add {
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
                }
            }
        }

        fun Project.wireToExtensions(
            moduleExt: ViaductModuleExtension,
            appExt: ViaductApplicationExtension
        ) {
            val pkgPrefixProv = tenantPackagePrefix(moduleExt, appExt)
            tenantPackagePrefix.set(pkgPrefixProv)

            val pkgProv = tenantPackage(moduleExt, appExt)
            tenantPackage.set(pkgProv)

            val outputAugmentedDir = outputAugmentedDir(pkgPrefixProv, pkgProv)
            outputDirectory.set(outputAugmentedDir)
        }

        private fun tenantPackagePrefix(
            moduleExt: ViaductModuleExtension,
            appExt: ViaductApplicationExtension
        ): Provider<String> {
            val suffixProv = moduleExt.modulePackageSuffix
            val blankSuffixProv = suffixProv.map { it.isBlank() }
            val pkgPrefixProv = blankSuffixProv.flatMap { blank ->
                if (blank) suffixProv else appExt.modulePackagePrefix
            }
            return pkgPrefixProv
        }

        private fun tenantPackage(
            moduleExt: ViaductModuleExtension,
            appExt: ViaductApplicationExtension,
        ): Provider<String> {
            val suffixProv = moduleExt.modulePackageSuffix
            val blankSuffixProv = suffixProv.map { it.isBlank() }
            val pkgProv = blankSuffixProv.flatMap { blank ->
                if (blank) {
                    appExt.modulePackagePrefix
                } else {
                    suffixProv
                }
            }
            return pkgProv
        }

        private fun Project.outputAugmentedDir(
            pkgPrefixProv: Provider<String>,
            pkgProv: Provider<String>,
        ): Provider<Directory> {
            val outputAugmentedDir = resolverBasesDirectory().flatMap { base ->
                pkgPrefixProv
                    .flatMap { pfx ->
                        pkgProv.map { pkg ->
                            (if (pkg.isBlank()) pfx else "$pfx.$pkg").trim('.').replace('.', '/')
                        }
                    }
                    .map { rel -> base.asFile.toPath().resolve(rel).toFile().apply { mkdirs() } }
                    .map { dir -> objects.directoryProperty().apply { set(dir) }.get() }
            }
            return outputAugmentedDir
        }
    }
