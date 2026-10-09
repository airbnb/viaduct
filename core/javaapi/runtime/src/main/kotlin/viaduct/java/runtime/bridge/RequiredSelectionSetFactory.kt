package viaduct.java.runtime.bridge

import javax.inject.Provider
import viaduct.bootstrap.FieldEntryConfig
import viaduct.bootstrap.SelectionsBlockConfig
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSets
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.bootstrap.executionregistry.RequiredSelectionSetSupport
import viaduct.engine.api.select.SelectionsParser
import viaduct.java.api.annotations.Variables
import viaduct.java.api.types.Arguments
import viaduct.java.api.variables.VariablesProvider
import viaduct.service.api.spi.CodeInjector

/**
 * Factory for creating [RequiredSelectionSets] for Java resolvers.
 *
 * This is the Java equivalent of the Kotlin [viaduct.tenant.runtime.bootstrap.RequiredSelectionSetFactory].
 * Parses build-time [FieldEntryConfig] registry descriptors, converts their variable declarations
 * to [SelectionSetVariable] instances, and discovers nested [VariablesProvider] classes for
 * dynamic variable provisioning.
 */
class RequiredSelectionSetFactory {
    /**
     * Create a [RequiredSelectionSets] from a build-time [FieldEntryConfig] registry descriptor.
     *
     * The selection fragments and variable declarations are read from the registry JSON (the same
     * data the APT extractor emitted from the `@Resolver` annotation), rather than re-reading the
     * runtime annotation. The nested [VariablesProvider] class, however, is still discovered
     * reflectively from [resolverClass] since it is not represented in the registry.
     *
     * @param schema The Viaduct schema containing type definitions
     * @param entry The field registry descriptor carrying the selection fragments and variables
     * @param resolverClass The resolver implementation class. Used both for attribution and for
     *        discovering nested [VariablesProvider] classes.
     * @param injector The injector used to obtain instances of the discovered VariablesProvider.
     * @param argumentsClass The Arguments class for the field this resolver targets, or null if
     *        the field has no arguments. Forwarded to the VariablesProvider executor so the
     *        provider receives a typed Arguments instance.
     * @return A [RequiredSelectionSets] containing the parsed object and query selections
     */
    fun mkRequiredSelectionSets(
        schema: EngineSchema,
        entry: FieldEntryConfig,
        resolverClass: Class<*>,
        injector: CodeInjector,
        argumentsClass: Class<out Arguments>? = null,
        grtPackagePrefix: String? = null,
    ): RequiredSelectionSets {
        val objectSelections = entry.objectSelections?.selections
            ?.takeIf { it.isNotBlank() }
            ?.let { SelectionsParser.parse(entry.typeName, it) }
        val querySelections = entry.querySelections?.selections
            ?.takeIf { it.isNotBlank() }
            ?.let { SelectionsParser.parse(schema.schema.queryType.name, it) }

        if (objectSelections == null && querySelections == null) {
            return RequiredSelectionSets.empty()
        }

        val variables = buildVariables(entry.objectSelections, entry.querySelections)
        val resolverId = "${entry.typeName}.${entry.fieldName}"
        val variablesProviderExecutor = mkVariablesProviderExecutor(resolverClass, injector, resolverId, argumentsClass, grtPackagePrefix)
        return RequiredSelectionSetSupport.buildRequiredSelectionSets(
            RequiredSelectionSetSupport.AssemblyInput(
                objectSelections = objectSelections,
                querySelections = querySelections,
                variables = variables,
                providerResolvers = listOfNotNull(variablesProviderExecutor),
                attribution = ExecutionAttribution.fromResolver(resolverClass.name),
            )
        )
    }

    /**
     * Convert the registry [SelectionsBlockConfig] variable declarations into [SelectionSetVariable]s,
     * mirroring [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory]'s handling.
     */
    private fun buildVariables(
        objectSelections: SelectionsBlockConfig?,
        querySelections: SelectionsBlockConfig?,
    ): List<SelectionSetVariable> = RequiredSelectionSetSupport.buildSelectionSetVariables(objectSelections, querySelections)

    /**
     * Discover a nested [VariablesProvider] class on [resolverClass] (annotated with [Variables])
     * and return a [VariablesProviderExecutorImpl] that adapts it to the engine SPI. Returns null
     * if no nested provider is found.
     */
    private fun mkVariablesProviderExecutor(
        resolverClass: Class<*>,
        injector: CodeInjector,
        resolverId: String,
        argumentsClass: Class<out Arguments>?,
        grtPackagePrefix: String?,
    ): VariablesProviderExecutorImpl? {
        val candidate = resolverClass.declaredClasses.firstOrNull { it.isAnnotationPresent(Variables::class.java) }
            ?: return null
        require(VariablesProvider::class.java.isAssignableFrom(candidate)) {
            "Class $candidate is annotated with @Variables but does not implement VariablesProvider"
        }
        val variableNames = candidate.getAnnotation(Variables::class.java).asNameSet()
        @Suppress("UNCHECKED_CAST")
        val provider: Provider<out VariablesProvider<*>> = injector.getProvider(candidate) as Provider<out VariablesProvider<*>>
        return VariablesProviderExecutorImpl(
            variableNames = variableNames,
            provider = provider,
            resolverId = resolverId,
            argumentsClass = argumentsClass,
            grtPackagePrefix = grtPackagePrefix,
        )
    }
}

/**
 * Parse a [Variables] annotation into the set of declared variable names.
 * Each entry has the form "name: Type"; whitespace is ignored.
 */
private fun Variables.asNameSet(): Set<String> = RequiredSelectionSetSupport.parseVariableTypeEntries(types.toList()).keys
