package viaduct.tenant.codegen.cli

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
import viaduct.engine.api.bootstrap.executionregistry.VariableProviderEntryConfig
import viaduct.engine.api.parse.DocumentParser
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.fromBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.graphqljava.readTypesFromFiles
import viaduct.graphql.utils.SelectionsParserUtils
import viaduct.tenant.codegen.ksp.PerSourceDescriptorFile
import viaduct.tenant.codegen.ksp.ResolverParams
import viaduct.tenant.codegen.ksp.ResolverParamsJsonCodec
import viaduct.tenant.codegen.ksp.SelectionsBlock
import viaduct.tenant.codegen.util.tenantModuleNameFromPackage

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
        tenantPackagePrefix: String? = null,
        outputDir: File,
        schemaBinary: File? = null,
        schemaFiles: List<File> = emptyList(),
    ) {
        writeRegistryFromDescriptors(
            descriptors = descriptorJsons.map(codec::decode),
            executorFactory = executorFactory,
            tenantPackage = tenantPackage,
            tenantPackagePrefix = tenantPackagePrefix,
            outputDir = outputDir,
            schemaBinary = schemaBinary,
            schemaFiles = schemaFiles,
        )
    }

    private fun writeRegistryFromDescriptors(
        descriptors: List<PerSourceDescriptorFile>,
        executorFactory: String,
        tenantPackage: String,
        tenantPackagePrefix: String?,
        outputDir: File,
        schemaBinary: File? = null,
        schemaFiles: List<File> = emptyList(),
    ) {
        val bootstrapClasses = descriptors.mapNotNull { it.bootstrapClass }
        if (bootstrapClasses.size > 1) {
            error(
                "Each tenant module may declare at most one @TenantBootstrapper class, " + "but found ${bootstrapClasses.size}: $bootstrapClasses",
            )
        }

        val fragmentsByName: Map<String, String> = buildFragmentsByName(descriptors)

        validateNameConflicts(descriptors, fragmentsByName)

        val tenantModuleName = tenantModuleNameFromPackage(tenantPackage, tenantPackagePrefix)

        require(schemaFiles.isNotEmpty() || schemaBinary == null) {
            "Schema files are required when a binary schema is provided"
        }

        if (schemaFiles.isNotEmpty()) {
            val typeDefinitionRegistry = readTypesFromFiles(schemaFiles.sortedBy(File::getAbsolutePath))
            val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(typeDefinitionRegistry)
            val viaductSchema = schemaBinary?.let(ViaductSchema::fromBinaryFile)
                ?: ViaductSchema.fromTypeDefinitionRegistry(typeDefinitionRegistry)
            validateAgainstSchema(descriptors, fragmentsByName, schema, viaductSchema, tenantModuleName)
        }

        val outputFile = outputDir.resolve(REGISTRY_RESOURCE_PATH).resolve("$tenantPackage.json")

        outputFile.parentFile.mkdirs()

        mapper.writerWithDefaultPrettyPrinter().writeValue(
            outputFile,
            buildExecutionRegistry(
                executorFactory = executorFactory,
                tenantPackage = tenantPackage,
                tenantPackagePrefix = tenantPackagePrefix,
                descriptors = descriptors,
                fragmentsByName = fragmentsByName,
                bootstrapClass = bootstrapClasses.singleOrNull(),
            ),
        )
    }

    private fun validateAgainstSchema(
        descriptors: List<PerSourceDescriptorFile>,
        fragmentsByName: Map<String, String>,
        schema: graphql.schema.GraphQLSchema,
        viaductSchema: ViaductSchema,
        tenantModuleName: String,
    ) {
        validateAssembledNamedFragments(descriptors, fragmentsByName, schema)
        validateAssembledRss(descriptors, fragmentsByName, schema, viaductSchema, tenantModuleName)
        validateAssembledOperations(descriptors, fragmentsByName, schema)
    }

    /**
     * Builds the name → text map for all @GraphQLFragment declarations, failing on duplicates.
     * Two @GraphQLFragment objects with the same fragment name are always an error regardless of
     * which leaf they come from.
     */
    private fun buildFragmentsByName(descriptors: List<PerSourceDescriptorFile>): Map<String, String> {
        val byName = mutableMapOf<String, String>()
        val errors = mutableListOf<String>()
        descriptors.flatMap { it.namedFragments }.forEach { fragment ->
            val name = parseFragmentName(fragment.text)
            if (byName.put(name, fragment.text) != null) {
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
        descriptors: List<PerSourceDescriptorFile>,
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
     * Validates every @GraphQLFragment against the schema via [NamedFragmentValidator], independently
     * of whether a resolver or operation spreads it.
     */
    private fun validateAssembledNamedFragments(
        descriptors: List<PerSourceDescriptorFile>,
        fragmentsByName: Map<String, String>,
        schema: graphql.schema.GraphQLSchema,
    ) {
        val fragments = descriptors.flatMap { it.namedFragments }
        if (fragments.isEmpty()) return

        val validator = NamedFragmentValidator(schema)
        val errors = mutableListOf<String>()
        fragments.forEach { validator.validate(it, fragmentsByName, errors) }

        if (errors.isNotEmpty()) {
            error("@GraphQLFragment validation failed at assembly:\n" + errors.joinToString("\n"))
        }
    }

    /**
     * Drives the per-field RSS validation loop, resolving cross-leaf fragments before handing each
     * selection block to [RequiredSelectionSetValidator] (which owns the rules).
     */
    private fun validateAssembledRss(
        descriptors: List<PerSourceDescriptorFile>,
        fragmentsByName: Map<String, String>,
        schema: graphql.schema.GraphQLSchema,
        viaductSchema: ViaductSchema,
        tenantModuleName: String,
    ) {
        val rssValidator = RequiredSelectionSetValidator(
            tenantCompilationSchema = schema,
            currentTenantModule = tenantModuleName,
            tenantCompilationViaductSchema = viaductSchema,
        )
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

    /** Validates every @GraphQLOperation against the schema via [GraphQLOperationValidator]. */
    private fun validateAssembledOperations(
        descriptors: List<PerSourceDescriptorFile>,
        fragmentsByName: Map<String, String>,
        schema: graphql.schema.GraphQLSchema,
    ) {
        val operations = descriptors.flatMap { it.namedOperations }
        if (operations.isEmpty()) return

        val validator = GraphQLOperationValidator(schema)
        val errors = mutableListOf<String>()
        operations.forEach { validator.validate(it, fragmentsByName, errors) }

        if (errors.isNotEmpty()) {
            error("@GraphQLOperation validation failed at assembly:\n" + errors.joinToString("\n"))
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
        tenantPackage: String,
        tenantPackagePrefix: String?,
        descriptors: List<PerSourceDescriptorFile>,
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
            tenantName = tenantModuleNameFromPackage(tenantPackage, tenantPackagePrefix),
            executorFactory = executorFactory,
            nodes = nodes,
            fields = fields,
            bootstrapClass = bootstrapClass,
            // Carried to runtime so ctx.query/ctx.mutation strings can resolve their fragment spreads.
            namedFragments = fragmentsByName.values.sorted(),
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

    private fun collectReachableFragmentNames(
        roots: List<SelectionSet>,
        knownFragments: Map<String, String>,
        alreadyDefined: Set<String>,
    ): List<String> = FragmentSpreadCollector.collectReachableExternalFragments(roots, knownFragments, alreadyDefined)

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
