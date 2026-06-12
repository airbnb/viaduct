package viaduct.tenant.codegen.cli

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
import viaduct.engine.api.bootstrap.executionregistry.VariableProviderEntryConfig
import viaduct.engine.api.parse.DocumentParser
import viaduct.graphql.utils.SelectionsParserUtils
import viaduct.tenant.codegen.ksp.ResolverDescriptorFile
import viaduct.tenant.codegen.ksp.ResolverParams
import viaduct.tenant.codegen.ksp.ResolverParamsJsonCodec
import viaduct.tenant.codegen.ksp.SelectionsBlock

internal object TenantModuleConfigAssembler {
    private const val REGISTRY_VERSION = "1"

    // jacksonMapperBuilder()/JsonMapper.builder() (the non-deprecated path) isn't available in the
    // Jackson version on the build classpath, so we keep configure() and suppress the deprecation.
    @Suppress("DEPRECATION")
    private val mapper: ObjectMapper = jacksonObjectMapper().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true).setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private val codec = ResolverParamsJsonCodec()

    fun writeRegistry(
        descriptorJsons: List<String>,
        executorFactory: String,
        tenantPackage: String,
        outputDir: File,
        schemaSdl: String? = null,
    ) {
        writeRegistryFromDescriptors(
            descriptors = descriptorJsons.map(codec::decode),
            executorFactory = executorFactory,
            tenantPackage = tenantPackage,
            outputDir = outputDir,
            schemaSdl = schemaSdl,
        )
    }

    private fun writeRegistryFromDescriptors(
        descriptors: List<ResolverDescriptorFile>,
        executorFactory: String,
        tenantPackage: String,
        outputDir: File,
        schemaSdl: String? = null,
    ) {
        val bootstrapClasses = descriptors.mapNotNull { it.bootstrapClass }
        if (bootstrapClasses.size > 1) {
            error(
                "Each tenant module may declare at most one @TenantBootstrapper class, " + "but found ${bootstrapClasses.size}: $bootstrapClasses",
            )
        }

        val fragmentsByName: Map<String, String> = buildFragmentsByName(descriptors)

        validateNameConflicts(descriptors, fragmentsByName)

        if (schemaSdl != null) {
            validateAssembledRss(descriptors, fragmentsByName, schemaSdl)
        }

        val outputFile = outputDir.resolve(REGISTRY_RESOURCE_PATH).resolve("$tenantPackage.json")

        outputFile.parentFile.mkdirs()

        mapper.writerWithDefaultPrettyPrinter().writeValue(
            outputFile,
            buildExecutionRegistry(
                executorFactory = executorFactory,
                descriptors = descriptors,
                fragmentsByName = fragmentsByName,
                bootstrapClass = bootstrapClasses.singleOrNull(),
            ),
        )
    }

    /**
     * Builds the name → text map for all @GraphQLFragment declarations, failing on duplicates.
     * Two @GraphQLFragment objects with the same fragment name are always an error regardless of
     * which leaf they come from.
     */
    private fun buildFragmentsByName(descriptors: List<ResolverDescriptorFile>): Map<String, String> {
        val byName = mutableMapOf<String, String>()
        val errors = mutableListOf<String>()
        descriptors.flatMap { it.namedFragments }.forEach { fragmentText ->
            val name = parseFragmentName(fragmentText)
            if (byName.put(name, fragmentText) != null) {
                errors.add("Duplicate @GraphQLFragment name '$name': fragment names must be unique across all leaves in a tenant module.")
            }
        }
        if (errors.isNotEmpty()) {
            error("Named fragment name conflicts:\n" + errors.joinToString("\n"))
        }
        return byName
    }

    /**
     * Detects cases where an RSS-local fragment name collides with a @GraphQLFragment name.
     * Two different RSS fragments may share the same helper-fragment name without conflict, but
     * if any local name matches a @GraphQLFragment name the intent is ambiguous.
     */
    private fun validateNameConflicts(
        descriptors: List<ResolverDescriptorFile>,
        fragmentsByName: Map<String, String>,
    ) {
        if (fragmentsByName.isEmpty()) return
        val errors = mutableListOf<String>()
        descriptors.flatMap { it.fields }.forEach { field ->
            field.selectionPairs().forEach { (block, typeName) ->
                val doc = DocumentParser.parse(normalizeSelections(block.selections, typeName))
                doc.getDefinitionsOfType(FragmentDefinition::class.java).map { it.name }.filter { it in fragmentsByName }.forEach { name ->
                    errors.add(
                        "Fragment name '$name' in ${field.implFqn} (${field.typeName}.${field.fieldName}) " + "conflicts with a @GraphQLFragment of the same name. " + "Rename either the local fragment or the @GraphQLFragment object.",
                    )
                }
            }
        }
        if (errors.isNotEmpty()) {
            error("RSS fragment name conflicts with @GraphQLFragment names:\n" + errors.joinToString("\n"))
        }
    }

    /**
     * Drives the per-field RSS validation loop, resolving cross-leaf fragments before handing each
     * selection block to [RequiredSelectionSetValidator] (which owns the rules).
     */
    private fun validateAssembledRss(
        descriptors: List<ResolverDescriptorFile>,
        fragmentsByName: Map<String, String>,
        schemaSdl: String,
    ) {
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(schemaSdl))
        val rssValidator = RequiredSelectionSetValidator(schema)
        val errors = mutableListOf<String>()

        descriptors.flatMap { it.fields }.forEach { field ->
            field.selectionPairs().forEach { pair ->
                rssValidator.validate(
                    normalizedSelections = normalizeSelections(pair.block.selections, pair.typeName),
                    expandedSelections = normalizeSelections(pair.block.withNamedFragmentsAppended(fragmentsByName, pair.typeName).selections, pair.typeName),
                    typeName = pair.typeName,
                    isQuery = pair.isQuery,
                    field = field,
                    errors = errors,
                )
            }
        }

        if (errors.isNotEmpty()) {
            error("RSS validation failed at assembly:\n" + errors.joinToString("\n"))
        }
    }

    /**
     * Normalizes a selections string to full fragment form, wrapping shorthand (`id name`) in a
     * `fragment Main on $typeName { ... }` envelope. Matches the behaviour of
     * [viaduct.engine.api.select.SelectionsParser] so build-time and runtime parsing agree.
     */
    private fun normalizeSelections(
        selections: String,
        typeName: String,
    ): String =
        if (SelectionsParserUtils.isShorthandForm(selections)) {
            SelectionsParserUtils.wrapShorthandAsFragment(selections, typeName)
        } else {
            selections
        }

    private fun buildExecutionRegistry(
        executorFactory: String,
        descriptors: List<ResolverDescriptorFile>,
        fragmentsByName: Map<String, String>,
        bootstrapClass: String?,
    ): ExecutionRegistryConfigFile {
        val nodes = descriptors.flatMap { it.nodes }.map { node ->
            NodeEntryConfig(
                typeName = node.typeName,
                isBatching = node.isBatching,
                isSelective = node.isSelective,
                attribution = node.attribution,
                tenantAPIData = mapOf(
                    "resolverClass" to node.implFqn,
                    "resolverBaseClass" to node.resolverBaseClass,
                ),
            )
        }

        val fields = descriptors.flatMap { it.fields }.map { field ->
            FieldEntryConfig(
                typeName = field.typeName,
                fieldName = field.fieldName,
                isBatching = field.isBatching,
                isSelective = field.isSelective,
                attribution = field.attribution,
                objectSelections = field.objectSelections?.withNamedFragmentsAppended(fragmentsByName, field.typeName)?.toEngineSelectionsBlock(),
                querySelections = field.querySelections?.withNamedFragmentsAppended(fragmentsByName, field.queryTypeName)?.toEngineSelectionsBlock(),
                tenantAPIData = mapOf(
                    "resolverClass" to field.implFqn,
                    "resolverBaseClass" to field.resolverBaseClass,
                    "returnTypeName" to field.returnTypeName,
                    "hasArguments" to field.hasArguments,
                    "queryTypeName" to field.queryTypeName,
                ),
            )
        }

        return ExecutionRegistryConfigFile(
            version = REGISTRY_VERSION,
            executorFactory = executorFactory,
            nodes = nodes,
            fields = fields,
            bootstrapClass = bootstrapClass,
        )
    }

    /**
     * Extracts the fragment name from a fragment definition string.
     * E.g., "fragment UserFields on User { id name }" → "UserFields".
     */
    private fun parseFragmentName(fragmentText: String): String = DocumentParser.parse(fragmentText).getDefinitionsOfType(FragmentDefinition::class.java).single().name

    /**
     * Appends fragment definitions transitively reachable from the spreads used in [selections]
     * but not already defined inline. Only fragments in [knownFragments] are considered.
     *
     * [typeName] is used to normalize shorthand selection strings into full fragment form before
     * parsing, matching the behaviour of [viaduct.engine.api.select.SelectionsParser].
     */
    private fun SelectionsBlock.withNamedFragmentsAppended(
        knownFragments: Map<String, String>,
        typeName: String,
    ): SelectionsBlock {
        if (knownFragments.isEmpty()) return this

        val normalized = normalizeSelections(selections, typeName)

        val doc = DocumentParser.parse(normalized)

        val existingFragmentDefs = doc.getDefinitionsOfType(FragmentDefinition::class.java)
        val alreadyDefined = existingFragmentDefs.map { it.name }.toSet()
        val allSelectionSets: List<SelectionSet> = existingFragmentDefs.mapNotNull { it.selectionSet }

        val referenced = collectReachableFragmentNames(allSelectionSets, knownFragments, alreadyDefined)
        if (referenced.isEmpty()) return this

        // When the entry fragment isn't named "Main" (e.g. the common `fragment _ on Type` pattern),
        // rename it so ParsedSelections.fromDocument can find the entry point in the resulting
        // multi-fragment document produced by appending the named fragments.
        val entryName = SelectionsParserUtils.findEntryPointFragment(existingFragmentDefs).name
        val baseSelections = if (entryName != SelectionsParserUtils.EntryPointFragmentName) {
            normalized.replaceFirst("fragment $entryName ", "fragment ${SelectionsParserUtils.EntryPointFragmentName} ")
        } else {
            normalized
        }

        val appended = referenced.joinToString("\n") { name -> knownFragments.getValue(name) }
        return copy(selections = "$baseSelections\n$appended")
    }

    /**
     * BFS over selection sets to collect the names of all fragment spreads transitively
     * reachable that are present in [knownFragments] but not in [alreadyDefined].
     */
    private fun collectReachableFragmentNames(
        roots: List<SelectionSet>,
        knownFragments: Map<String, String>,
        alreadyDefined: Set<String>,
    ): List<String> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()
        val queue = ArrayDeque<SelectionSet>()
        roots.forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.selections.forEach { selection ->
                when (selection) {
                    is FragmentSpread -> {
                        val name = selection.name
                        if (visited.add(name) && name !in alreadyDefined && name in knownFragments) {
                            result.add(name)
                            // Parse the fragment body to find its own spreads transitively.
                            DocumentParser.parse(knownFragments.getValue(name)).getDefinitionsOfType(FragmentDefinition::class.java).mapNotNull { it.selectionSet }.forEach { queue.add(it) }
                        }
                    }

                    is Field -> selection.selectionSet?.let { queue.add(it) }
                    is InlineFragment -> queue.add(selection.selectionSet)
                }
            }
        }
        return result
    }

    /** A selection block, the type its fragment is expected to be on, and whether it is the query (vs. object) selection set. */
    private data class SelectionPair(
        val block: SelectionsBlock,
        val typeName: String,
        val isQuery: Boolean,
    )

    private fun ResolverParams.Field.selectionPairs(): List<SelectionPair> =
        listOfNotNull(
            objectSelections?.let { SelectionPair(it, typeName, isQuery = false) },
            querySelections?.let { SelectionPair(it, queryTypeName, isQuery = true) },
        )

    private fun SelectionsBlock.toEngineSelectionsBlock(): SelectionsBlockConfig {
        return SelectionsBlockConfig(
            selections = selections,
            variablesProviders = variablesProviders.map { provider ->
                VariableProviderEntryConfig(
                    providedVariables = provider.providedVariables,
                    providerVariablesAPIData = ProviderVariablesAPIData(
                        type = provider.kind,
                        path = requireNotNull(provider.path) {
                            "VariableProviderDescriptor.path must not be null for variable '${provider.name}'"
                        },
                    ),
                )
            },
        )
    }
}
