package viaduct.gradle.task

import java.io.File
import javaResolverBasesDirectory
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import viaduct.gradle.CodegenWorkAction
import viaduct.gradle.ViaductApplicationExtension
import viaduct.gradle.ViaductModuleExtension
import viaduct.gradle.runCodegen

@CacheableTask
abstract class GenerateJavaResolverBasesTask
    @Inject
    constructor(
        private val workerExecutor: WorkerExecutor
    ) : DefaultTask() {
        init {
            group = "viaduct"
            description = "Generate resolver base Java sources from central schema and module partition."
        }

        @get:Classpath
        abstract val classpath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val centralSchemaFiles: ConfigurableFileCollection

        @get:Input
        abstract val tenantPackagePrefix: Property<String>

        @get:Input
        abstract val tenantPackage: Property<String>

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            val resolverDir = outputDirectory.get().asFile
            if (resolverDir.exists()) resolverDir.deleteRecursively()
            resolverDir.mkdirs()

            val grtDiscardDir = temporaryDir.resolve("discard-grts").apply {
                deleteRecursively()
                mkdirs()
            }

            val fullPackage = listOf(tenantPackagePrefix.get(), tenantPackage.get())
                .filter { it.isNotBlank() }
                .joinToString(".")

            workerExecutor.runCodegen(
                classpath,
                CodegenWorkAction.MainClasses.JAVA_GRTS_GENERATOR,
                listOf(
                    "--schema_files",
                    centralSchemaFiles.files.map(File::getAbsolutePath).sorted().joinToString(","),
                    "--grt_output_dir",
                    grtDiscardDir.absolutePath,
                    "--grt_package",
                    fullPackage,
                    "--resolver_generated_dir",
                    resolverDir.absolutePath,
                    "--tenant_package",
                    fullPackage
                )
            )
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
            val outputAugmentedDir = javaResolverBasesDirectory().flatMap { base ->
                pkgPrefixProv
                    .flatMap { pfx ->
                        pkgProv.map { pkg ->
                            (if (pkg.isBlank()) pfx else "$pfx.$pkg").trim('.').replace('.', '/')
                        }
                    }
                    .map { rel -> base.asFile.toPath().resolve(rel).toFile() }
                    .map { dir -> objects.directoryProperty().apply { set(dir) }.get() }
            }
            return outputAugmentedDir
        }
    }
