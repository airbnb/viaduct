package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

internal class ResolverDescriptorProcessor(
    private val codeGenerator: CodeGenerator,
    private val jsonCodec: ResolverParamsJsonCodec,
) {
    fun write(
        sourceFile: KSFile,
        descriptorFile: ResolverDescriptorFile,
    ): Boolean {
        if (descriptorFile.isEmpty()) {
            return false
        }

        val dependencies = Dependencies(
            aggregating = false,
            sources = arrayOf(sourceFile),
        )

        val output = codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = buildOutputPath(sourceFile),
            extensionName = JSON_EXTENSION,
        )

        val json = jsonCodec.encode(descriptorFile)

        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            writer.write(json)
        }

        return true
    }

    private fun buildOutputPath(sourceFile: KSFile): String {
        val packagePath = sourceFile.packageName.asString().replace('.', '/')
        val baseName = sourceFile.fileName.substringBeforeLast('.')

        return if (packagePath.isBlank()) {
            "$DESCRIPTOR_ROOT/$baseName"
        } else {
            "$DESCRIPTOR_ROOT/$packagePath/$baseName"
        }
    }

    private companion object {
        const val DESCRIPTOR_ROOT = "viaduct-registry"
        const val JSON_EXTENSION = "json"
    }
}
