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
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Extracts GraphQL schemas from `@TestSchema` annotations in compiled testFixtures class files
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
    /** Compiled testFixtures class directories. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val packagesSeen = mutableMapOf<String, String>()

        for (classesDir in classesDirs) {
            if (!classesDir.isDirectory) continue
            classesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { classFile ->
                    val schema = extractSchemaFromClassFile(classFile) ?: return@forEach

                    val relPath = classFile.relativeTo(classesDir).path
                    val pkgPath = classFile.parentFile.relativeTo(classesDir).path
                    val className = relPath.removeSuffix(".class").replace(File.separatorChar, '.')

                    val existing = packagesSeen[pkgPath]
                    if (existing != null) {
                        error(
                            "Two contracts in package '${pkgPath.replace(File.separatorChar, '.')}': " +
                                "$existing and $className"
                        )
                    }
                    packagesSeen[pkgPath] = className

                    val schemaFile = File(outDir, "$pkgPath/schema.graphql")
                    schemaFile.parentFile.mkdirs()
                    schemaFile.writeText(schema.trimIndent().trim())
                }
        }

        logger.info("Extracted {} contract schemas", packagesSeen.size)
    }
}

// ── ASM-based extraction ────────────────────────────────────────────────────

internal const val TEST_SCHEMA_DESCRIPTOR = "Lviaduct/tenant/runtime/fixtures/TestSchema;"

/**
 * Reads a `.class` file and returns the `@TestSchema` value if the annotation is present,
 * or `null` if the class doesn't carry the annotation.
 */
fun extractSchemaFromClassFile(classFile: File): String? {
    val reader = ClassReader(classFile.readBytes())
    var schema: String? = null

    reader.accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean
            ): AnnotationVisitor? {
                if (descriptor == TEST_SCHEMA_DESCRIPTOR && visible) {
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(
                            name: String?,
                            value: Any?
                        ) {
                            if (name == "value" && value is String) {
                                schema = value
                            }
                        }
                    }
                }
                return null
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
    )

    return schema
}
