package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated

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
 */
class RegistryExtractorProcessor(
    environment: SymbolProcessorEnvironment,
) : SymbolProcessor {
    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger: KSPLogger = environment.logger
    private val jsonCodec = ResolverParamsJsonCodec()

    private var generated: Boolean = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) {
            logger.infoRegistryExtractor("Skipping because output was already generated")
            return emptyList()
        }

        val descriptorsByFile = ResolverParamsExtractor(
            resolver = resolver,
            logger = logger,
        ).extractByFile()

        val writer = ResolverDescriptorProcessor(
            codeGenerator = codeGenerator,
            jsonCodec = jsonCodec,
        )

        var writtenCount = 0
        for ((sourceFile, descriptorFile) in descriptorsByFile) {
            if (writer.write(sourceFile = sourceFile, descriptorFile = descriptorFile)) {
                writtenCount += 1
            }
        }

        logger.infoRegistryExtractor(
            "Generated {} registry descriptor file(s)",
            writtenCount,
        )

        generated = true
        return emptyList()
    }
}
