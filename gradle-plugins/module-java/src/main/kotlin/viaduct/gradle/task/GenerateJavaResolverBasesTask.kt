package viaduct.gradle.task

import java.io.File
import javaResolverBasesDirectory
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
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
import viaduct.gradle.ViaductPluginCommon
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
                    ViaductPluginCommon.JAVA_GRT_PACKAGE,
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

            // The codegen CLI writes files at {outputDir}/{package-as-path}/resolverbases/*.java,
            // so outputDirectory must be the source set root — not a pre-augmented subdirectory.
            outputDirectory.set(javaResolverBasesDirectory())
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
    }
