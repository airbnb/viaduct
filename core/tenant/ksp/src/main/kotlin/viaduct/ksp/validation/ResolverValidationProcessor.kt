package viaduct.ksp.validation

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import graphql.validation.Validator

/**
 * This is the entry point of the interface used by kotlin compiler plugins to
 * integrate into Kotlin Symbol Processing. ResolverSelectionSetProcessor
 * has the core processing logic
 */
class ResolverValidationProcessor : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val fragmentValidator = Validator()
        return ResolverSelectionSetProcessor(environment, fragmentValidator)
    }
}
