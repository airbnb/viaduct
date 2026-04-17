package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile

/**
 * Isolating KSP processor that emits one JSON descriptor per source file that
 * contains at least one `@Resolver`-annotated class. Descriptors are written to
 * `viaduct-registry/<package-path>/`. Each descriptor is associated with exactly one
 * source file via [Dependencies], so KSP's incremental processing handles invalidation
 * naturally: when a `.kt` file changes, only its descriptor is regenerated; when a
 * `.kt` file is deleted, KSP removes the corresponding descriptor.
 *
 * Source files with no `@Resolver` annotations produce no output — this keeps the
 * descriptor directory scoped to only the files the aggregation CLI cares about.
 *
 * Currently a stub that writes `{ "sourceFile": "<filename>" }` — this will evolve
 * into the real ResolverParams extractor.
 */
class RegistryExtractorProcessor(
    private val environment: SymbolProcessorEnvironment,
) : SymbolProcessor {
    private val codeGenerator: CodeGenerator = environment.codeGenerator

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Find all @Resolver-annotated classes, grouped by their containing file.
        val resolversByFile = resolver.getSymbolsWithAnnotation(RESOLVER_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.containingFile != null }
            .groupBy { it.containingFile!! }

        for ((file, _) in resolversByFile) {
            writeDescriptor(file)
        }

        return emptyList()
    }

    private fun writeDescriptor(file: KSFile) {
        val packagePath = file.packageName.asString().replace('.', '/')
        val baseName = file.fileName.removeSuffix(".kt")

        // Each descriptor depends on exactly one source file — this is what makes the
        // processor isolating. KSP tracks this dependency: if the source file changes,
        // only this descriptor is regenerated; if the source file is deleted, this
        // descriptor is cleaned up.
        val dependencies = Dependencies(aggregating = false, sources = arrayOf(file))

        // Write into viaduct-registry/<package-path>/<FileName>.json within the KSP
        // resources output directory.
        val outputStream = codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = "viaduct-registry/$packagePath/$baseName",
            extensionName = "json",
        )

        // Deterministic output: sorted keys, consistent formatting. This ensures that
        // cosmetic changes to a .kt file (comments, formatting) that don't affect the
        // extracted data produce byte-identical descriptors, preserving downstream
        // incrementality.
        outputStream.writer().use { writer ->
            writer.write("{\n")
            writer.write("  \"sourceFile\": \"${file.fileName}\"\n")
            writer.write("}\n")
        }
    }

    companion object {
        private const val RESOLVER_ANNOTATION = "viaduct.api.Resolver"
    }
}
