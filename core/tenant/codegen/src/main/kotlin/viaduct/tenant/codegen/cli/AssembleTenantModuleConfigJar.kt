package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
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

    private val outputJar: File by option("--output-jar")
        .file(mustExist = false, canBeDir = false)
        .required()

    override fun run() {
        val descriptorJsons = readDescriptorJsons()
        val outputDir = Files.createTempDirectory("tenant-module-config").toFile()

        try {
            TenantModuleConfigAssembler.writeRegistry(
                descriptorJsons = descriptorJsons,
                executorFactory = MODERN_KOTLIN_EXECUTOR_FACTORY,
                tenantPackage = tenantPackage,
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
