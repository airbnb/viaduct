package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteChildrenAsRoot

/**
 * Bazel-facing wrapper that reads descriptor JSON entries directly from KSP gensrc jars and
 * packages the assembled tenant module config as a resource jar.
 */
class AssembleTenantModuleConfigJar : CliktCommand(
    name = "assemble-tenant-module-config-jar",
) {
    private val descriptorJarsList: File by option("--descriptor-jars-list")
        .file(mustExist = true, canBeDir = false)
        .required()

    private val tenantPackage: String by option("--tenant-package")
        .required()

    private val tenantPackagePrefix: String? by option("--tenant-package-prefix")

    private val schemaSdl: File? by option("--schema-sdl")
        .file(mustExist = true, canBeDir = false)

    private val schemaBinary: File? by option("--schema-binary")
        .file(mustExist = true, canBeDir = false)

    /**
     * FQN of the `ExecutorFactory` recorded in the assembled config. Defaults to the Kotlin factory
     * so existing callers are unaffected; the Java path passes
     * `viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory` instead.
     */
    private val executorFactory: String by option("--executor-factory")
        .default(MODERN_KOTLIN_EXECUTOR_FACTORY)

    private val requireNonEmpty: Boolean by option(
        "--require-non-empty",
        help = "Fail if no descriptors are found. Use for contract tests where an empty registry " +
            "means the KSP/APT plugin silently produced nothing."
    )
        .flag(default = false)

    private val outputJar: File by option("--output-jar")
        .file(mustExist = false, canBeDir = false)
        .required()

    override fun run() {
        val descriptorJsons = readDescriptorJsons()

        if (requireNonEmpty && descriptorJsons.isEmpty()) {
            throw IllegalStateException(
                "No descriptors found for tenant package '$tenantPackage' but --require-non-empty was set. " +
                    "This typically means the KSP registry-extractor plugin did not emit any descriptors. " +
                    "Check that the KSP plugin is applied and that resolver classes are annotated correctly."
            )
        }

        val outputDir = Files.createTempDirectory("tenant-module-config").toFile()

        try {
            TenantModuleConfigAssembler.writeRegistry(
                descriptorJsons = descriptorJsons,
                executorFactory = executorFactory,
                tenantPackage = tenantPackage,
                tenantPackagePrefix = tenantPackagePrefix,
                schemaSdl = schemaSdl?.readText(),
                schemaBinary = schemaBinary,
                outputDir = outputDir,
            )

            requireNotNull(outputJar.parentFile) {
                "--output-jar must include a parent directory"
            }.mkdirs()
            outputJar.zipAndWriteChildrenAsRoot(outputDir)
        } finally {
            outputDir.deleteRecursively()
        }
    }

    private fun readDescriptorJsons(): List<String> {
        val descriptorEntryPrefix = "$KSP_DESCRIPTOR_SUBDIR/${tenantPackage.replace('.', '/')}/"
        val descriptorJsonsByEntry = linkedMapOf<String, String>()

        descriptorJarsList.readLines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::File)
            .sortedBy(File::getAbsolutePath)
            .forEach { jarFile ->
                JarFile(jarFile).use { jar ->
                    jar.entryNamesStartingWith(descriptorEntryPrefix)
                        .forEach { entryName ->
                            descriptorJsonsByEntry[entryName] = jar.getInputStream(jar.getJarEntry(entryName))
                                .bufferedReader()
                                .use { it.readText() }
                        }
                }
            }

        return descriptorJsonsByEntry.entries
            .sortedBy { it.key }
            .map { it.value }
    }

    private fun JarFile.entryNamesStartingWith(prefix: String): List<String> {
        val names = mutableListOf<String>()
        val entries = entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".json")) {
                names += entry.name
            }
        }
        return names.sorted()
    }

    private companion object {
        const val KSP_DESCRIPTOR_SUBDIR = "viaduct-registry"
        const val MODERN_KOTLIN_EXECUTOR_FACTORY = "viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory"
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = AssembleTenantModuleConfigJar().main(args)
    }
}
