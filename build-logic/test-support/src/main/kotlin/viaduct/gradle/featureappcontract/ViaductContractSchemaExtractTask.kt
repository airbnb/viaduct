package viaduct.gradle.featureappcontract

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Extracts GraphQL schemas from `@TestSchema` annotations in testFixtures source files
 * and writes one `schema.graphql` file per contract, keyed by package path.
 *
 * Output layout:
 * ```
 * outputDir/
 *   viaduct/tenant/runtime/execution/objectresolver/schema.graphql
 *   viaduct/tenant/runtime/execution/defaults/schema.graphql
 *   ...
 * ```
 */
@CacheableTask
abstract class ViaductContractSchemaExtractTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val packagesSeen = mutableMapOf<String, File>()

        for (file in sourceFiles) {
            if (!file.isFile) continue
            if (!hasTestSchemaAnnotation(file)) continue

            val info = validateContractFile(file, packagesSeen)
            packagesSeen[info.pkgPath] = file

            val schemaFile = File(outDir, "${info.pkgPath}/schema.graphql")
            schemaFile.parentFile.mkdirs()
            extractSchemaFromAnnotation(file, schemaFile)
        }

        logger.info("Extracted {} contract schemas", packagesSeen.size)
    }
}

// ── Pure helper functions ────────────────────────────────────────────────────

data class ContractFileInfo(val pkg: String, val pkgPath: String)

/**
 * Returns `true` if the file contains an `@TestSchema(` annotation.
 */
fun hasTestSchemaAnnotation(file: File): Boolean {
    return try {
        file.readText().contains("@TestSchema(")
    } catch (_: java.io.IOException) {
        false
    }
}

/**
 * Validates that a contract file has a package declaration and that no other
 * contract in [packagesSeen] already occupies that package (Invariant 2).
 */
fun validateContractFile(
    file: File,
    packagesSeen: Map<String, File>
): ContractFileInfo {
    val pkg = extractPackageFromFile(file)
        ?: error("Contract file '${file.name}' has no package declaration.")
    val pkgPath = pkg.replace('.', '/')

    val existing = packagesSeen[pkgPath]
    if (existing != null) {
        error(
            "Two contract files in package '$pkg': " +
                "${existing.name} and ${file.name}. " +
                "Each contract must be in its own package."
        )
    }
    return ContractFileInfo(pkg, pkgPath)
}

/**
 * Extracts the package declaration from a Kotlin or Java source file.
 */
fun extractPackageFromFile(file: File): String? {
    return try {
        val content = file.readText()
        val packagePattern = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)
        packagePattern.find(content)?.groupValues?.get(1)
    } catch (_: java.io.IOException) {
        null
    }
}

/**
 * Extracts the GraphQL SDL from a `@TestSchema("""...""")` annotation and writes it
 * to [outputFile].
 */
fun extractSchemaFromAnnotation(
    sourceFile: File,
    outputFile: File
) {
    val content = sourceFile.readText()

    val annotationPattern = Regex(
        """@TestSchema\(\s*"{3}(.*?)"{3}\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    val match = annotationPattern.find(content)
        ?: error("No @TestSchema annotation found in ${sourceFile.name}")

    val schemaContent = match.groupValues[1].trimIndent().trim()
    require(schemaContent.isNotBlank()) {
        "@TestSchema annotation in ${sourceFile.name} has empty schema content."
    }

    outputFile.parentFile.mkdirs()
    outputFile.writeText(schemaContent)
}
