package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * Entry point for the registry-extractor KSP processor, discovered via
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`.
 */
class RegistryExtractorProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return RegistryExtractorProcessor(environment)
    }
}
