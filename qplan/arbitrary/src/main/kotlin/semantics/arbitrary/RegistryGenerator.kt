package semantics.arbitrary

import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation
import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import model.EngineErrorData
import model.EngineInputData
import model.EngineOutputData
import model.EngineOutputListData
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.Fragment
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.Selection
import model.SelectionForest
import model.SourceSchemaAdapter
import viaduct.engine.api.EngineObjectData
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectOf
import model.requireType
import model.registry.ProviderFragment
import model.selectionForestOf
import model.toMaterializeSelectionForest
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.fromQueryField
import model.testing.nodeResolverOf
import model.testing.selectionAwareNodeResolverOf
import model.testing.withErrorArguments
import model.toSelectionForest
import model.usedVariables
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ResolverProgramKind {
    CONSTANT,
    INPUT_SENSITIVE,
    ARGUMENT_SENSITIVE,
    INPUT_AND_ARGUMENT_SENSITIVE,
}

enum class ResolverProgramMutation {
    NONE,
    CACHE_FIRST_INPUT,
    CACHE_FIRST_ARGUMENTS,
    DUPLICATE_APPLICATION,
    APPLICATION_ORDINAL_CONTAMINATION,
}

data class RegistryFeatures(
    val inputSensitiveResolvers: Int,
    val argumentSensitiveResolvers: Int,
    val inputAndArgumentSensitiveResolvers: Int,
    val sometimesPassiveFieldCount: Int,
    val resolverErrorArgumentCount: Int,
    val variableCount: Int,
    val fromArgumentVariableCount: Int,
    val fromArgumentNestedPathVariableCount: Int,
    val fromArgumentNullableTraversalVariableCount: Int,
    val fromProviderVariableCount: Int,
    val fromProviderArgumentSensitiveVariableCount: Int,
    val fromObjectFieldVariableCount: Int,
    val literalVariableConvergenceCount: Int,
    val fromObjectFieldLiteralVariableConvergenceCount: Int,
    val sameFragmentVariableReuseCount: Int,
    val singletonCoercionVariableCount: Int,
    val passiveTopLevelFromObjectFieldVariableUseCount: Int,
    val fromObjectFieldProviderArgumentVariableCount: Int,
    val maximumFromObjectFieldPathLength: Int,
    val maximumFromObjectFieldVariableUseDepth: Int,
    val maximumVariablesPerOwner: Int,
    val hasNestedInputVariable: Boolean,
    val hasListVariable: Boolean,
    val hasNullableProvider: Boolean,
    val hasAbstractProviderPath: Boolean,
    val hasAbstractResolverFragment: Boolean,
    val queryFragmentCount: Int = 0,
    val fromQueryFieldVariableCount: Int = 0,
    val maximumFromQueryFieldPathLength: Int = 0,
    val maximumFromQueryFieldVariableUseDepth: Int = 0,
    val maximumParentSelectionDepth: Int = 0,
    val resolverOutputParentFieldCount: Int = 0,
    val generatedRootFieldReferenceCount: Int = 0,
    val rootFieldReferenceTargetCount: Int = 0,
    val rootFieldReferenceFallbackCount: Int = 0,
    val generatedNodeRootFieldReferenceCount: Int = 0,
)

/**
 * A registry recipe whose resolver coordinates, potential output paths, and value plans are
 * independent of any generated query. Calling [world] materializes it against one canonical
 * decoded schema.
 */
class ArbitraryRegistry internal constructor(
    val fieldResolverCoordinates: Set<FieldCoordinate>,
    val nodeResolverTypes: Set<String>,
    val outputSelectionSets: Map<String, Set<String>>,
    val objectFragmentSources: Map<FieldCoordinate, String>,
    val queryFragmentSources: Map<FieldCoordinate, String>,
    val variableProviderSources: Map<String, String>,
    internal val fieldValues: Map<FieldCoordinate, ValuePlan>,
    internal val nodeValues: Map<String, ValuePlan>,
    internal val objectFragments: Map<FieldCoordinate, FragmentPlan>,
    internal val queryFragments: Map<FieldCoordinate, FragmentPlan>,
    internal val variableProviders: List<VariableProviderPlan>,
    internal val resolverPrograms: Map<FieldCoordinate, ResolverProgramKind>,
    val parentDemandOwnerFields: Map<FieldCoordinate, Int> = emptyMap(),
    internal val schemaRootFieldReferenceFamily: RootFieldReferenceFamily? = null,
    val features: RegistryFeatures,
) {
    private val applicationLog = ResolutionApplicationLog()
    private val selectiveNodeApplicationLog = SelectiveNodeResolverApplicationLog()
    private val applicationCounts = ConcurrentHashMap<FieldCoordinate, Long>()

    /** Source resolver fields whose generated fragments consume a `FromArgument` variable. */
    val fromArgumentVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .filterIsInstance<FromArgumentVariableProviderPlan>()
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated fragments consume a FromProvider variable. */
    val fromProviderVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .filterIsInstance<FromProviderVariableProviderPlan>()
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields with a variable read through an input-object argument path. */
    val nestedFromArgumentVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .filterIsInstance<FromArgumentVariableProviderPlan>()
            .filter { provider -> provider.inputPath.isNotEmpty() }
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose argument path may encounter a null input object. */
    val nullableTraversalFromArgumentVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .filterIsInstance<FromArgumentVariableProviderPlan>()
            .filter(FromArgumentVariableProviderPlan::nullableTraversal)
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated fragments consume a FromObjectField variable. */
    val fromObjectFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated fragments consume a FromQueryField variable. */
    val fromQueryFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.QUERY)
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated object-path provider crosses an abstract type. */
    val abstractFromObjectFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .filter(FromFieldVariableProviderPlan::abstractPath)
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolvers whose provider key arguments depend on another object-path variable. */
    val fromObjectFieldProviderArgumentVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .filter { provider -> provider.providerArgumentVariableNames().isNotEmpty() }
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose path variable is used below a passive top-level branch. */
    val passiveTopLevelFromObjectFieldVariableUseOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .filter { provider -> provider.topLevelUseField !in fieldResolverCoordinates }
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated fragments consume a nested FromObjectField path. */
    val nestedFromObjectFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .filter { provider -> provider.responsePath().size > 1 }
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated fragments use a FromObjectField variable below a top-level selection. */
    val nestedFromObjectFieldVariableUseOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .filter { provider -> provider.useDepth > 1 }
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated nested provider path encounters a planned null intermediate. */
    val nullIntermediateFromObjectFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .filter { provider ->
                provider.intermediateOutcome(fieldValues) == ProviderIntermediateOutcome.NULL
            }.mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated nested provider path encounters a planned error intermediate. */
    val errorIntermediateFromObjectFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .filter { provider ->
                provider.intermediateOutcome(fieldValues) == ProviderIntermediateOutcome.ERROR
            }.mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Directed owner pairs where the first owner's variable-bearing fragment selects the second owner. */
    val fromObjectFieldVariableOwnerDependencies: Set<Pair<FieldCoordinate, FieldCoordinate>> =
        variableProviders
            .fromField(ProviderFragment.OBJECT)
            .mapNotNullTo(linkedSetOf()) { provider ->
                provider.topLevelUseField
                    .takeIf { useField -> useField in fromObjectFieldVariableOwnerFields }
                    ?.let { useField -> provider.owner to useField }
            }

    fun sourceResolverHasFromArgumentVariables(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in fromArgumentVariableOwnerFields

    fun sourceResolverIsRootFieldReferenceFallback(
        canonicalField: FieldCoordinate,
    ): Boolean =
        schemaRootFieldReferenceFamily?.fallbackCoordinate == sourceField(canonicalField)

    fun sourceResolverIsRootFieldReferenceExtension(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in schemaRootFieldReferenceFamily?.extensionCoordinates.orEmpty()

    fun sourceFieldIsRootFieldReferenceOverride(field: FieldCoordinate): Boolean =
        field in schemaRootFieldReferenceFamily?.consumerValueCoordinates.orEmpty()

    fun sourceResolverHasFromObjectFieldVariables(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in fromObjectFieldVariableOwnerFields

    fun sourceResolverHasFromProviderVariables(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in fromProviderVariableOwnerFields

    fun sourceResolverHasFromQueryFieldVariables(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in fromQueryFieldVariableOwnerFields

    fun sourceResolverHasNestedFromObjectFieldVariable(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in nestedFromObjectFieldVariableOwnerFields

    /** Maps a fixture-lowered application coordinate back to its generated source resolver. */
    fun sourceResolverCoordinate(canonicalField: FieldCoordinate): FieldCoordinate =
        sourceField(canonicalField)

    fun clearResolutionWitness() {
        applicationLog.clear()
        selectiveNodeApplicationLog.clear()
    }

    fun resolutionWitness(): ResolutionWitness = applicationLog.snapshot()

    fun selectiveNodeResolverApplications(): List<SelectiveNodeResolverApplicationRecord> =
        selectiveNodeApplicationLog.snapshot()

    fun <T> withoutResolutionWitnessCapture(block: () -> T): T =
        applicationLog.withoutRecording {
            selectiveNodeApplicationLog.withoutRecording(block)
        }

    fun clearResolutionApplicationCounts() {
        applicationCounts.clear()
    }

    fun resolutionApplicationCounts(): Map<FieldCoordinate, Long> =
        applicationCounts.toMap()

    fun resolverProgram(sourceField: FieldCoordinate): ResolverProgramKind =
        if (sourceField.fieldName == "V_A_typename") {
            ResolverProgramKind.CONSTANT
        } else {
            resolverPrograms.getValue(sourceField)
        }

    fun applicationProgram(
        schema: ArbitrarySchema,
        canonicalField: FieldCoordinate,
    ): ResolverProgramKind =
        if (canonicalField.isNodeLoader(schema)) {
            ResolverProgramKind.ARGUMENT_SENSITIVE
        } else {
            resolverProgram(sourceField(canonicalField))
        }

    fun applicationHasDependencies(
        schema: ArbitrarySchema,
        canonicalField: FieldCoordinate,
    ): Boolean =
        when {
            canonicalField.fieldName == "V_A_typename" -> false
            canonicalField.isNodeLoader(schema) -> false
            else -> {
                val sourceField = sourceField(canonicalField)
                objectFragmentSources.getValue(sourceField).isNotEmpty() ||
                    queryFragmentSources.getValue(sourceField).isNotEmpty()
            }
        }

    /** Recursive selection counts for every generated field-resolver object fragment. */
    fun objectFragmentSelectionCounts(): List<Int> =
        objectFragments.values.map(FragmentPlan::selectionCount)

    /** Longest selection-path depths for every generated field-resolver object fragment. */
    fun objectFragmentDepths(): List<Int> =
        objectFragments.values.map(FragmentPlan::selectionDepth)

    fun nodeLoaderPossibleTypes(
        @Suppress("UNUSED_PARAMETER")
        schema: ArbitrarySchema,
        canonicalField: FieldCoordinate,
    ): Set<String> {
        if (canonicalField != FieldCoordinate("Query", "node")) return emptySet()
        return nodeResolverTypes
    }

    private fun sourceField(canonicalField: FieldCoordinate): FieldCoordinate {
        return canonicalField
    }

    private fun FieldCoordinate.isNodeLoader(schema: ArbitrarySchema): Boolean {
        return nodeLoaderPossibleTypes(schema, this).isNotEmpty()
    }

    /** Creates request-local observation state; constructing a model world installs no hooks. */
    fun resolverObserver(
        captureSuppliedDemand: Boolean = false,
        captureResolutionWitness: Boolean = true,
        captureResolutionApplicationCounts: Boolean = !captureResolutionWitness,
        resolverProgramMutation: ResolverProgramMutation = ResolverProgramMutation.NONE,
    ): CorrectnessResolverObserver {
        require(captureResolutionWitness || !captureSuppliedDemand)
        require(!(captureResolutionWitness && captureResolutionApplicationCounts))
        fun recordApplication(
            coordinate: FieldCoordinate,
            arguments: Arguments.Resolved,
            input: EngineObjectData.Sync,
            suppliedDemand: SelectionForest?,
        ) {
            if (captureResolutionWitness) {
                applicationLog.record(
                    field = coordinate,
                    arguments = arguments,
                    input = input,
                    suppliedDemand = suppliedDemand.takeIf { captureSuppliedDemand },
                )
            } else if (captureResolutionApplicationCounts) {
                applicationCounts.compute(coordinate) { _, previous ->
                    Math.addExact(previous ?: 0L, 1L)
                }
            }
        }
        return object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val coordinate = FieldCoordinate(observation.field.containingDef.name, observation.field.name)
                recordApplication(coordinate, observation.arguments, observation.input, observation.suppliedDemand)
                if (resolverProgramMutation == ResolverProgramMutation.DUPLICATE_APPLICATION) {
                    recordApplication(coordinate, observation.arguments, observation.input, observation.suppliedDemand)
                }
            }
        }
    }

    fun world(
        schema: ArbitrarySchema,
        resolverProgramMutation: ResolverProgramMutation = ResolverProgramMutation.NONE,
        selectiveNodeResolvers: Boolean = false,
    ): TestWorld =
        world(
            schemaSDL = schema.sdl,
            resolverProgramMutation = resolverProgramMutation,
            selectiveNodeResolvers = selectiveNodeResolvers,
        )

    fun world(
        schemaSDL: String,
        resolverProgramMutation: ResolverProgramMutation = ResolverProgramMutation.NONE,
        selectiveNodeResolvers: Boolean = false,
    ): TestWorld {
        val firstInputs = ConcurrentHashMap<FieldCoordinate, EngineObjectData.Sync>()
        val firstArguments = ConcurrentHashMap<FieldCoordinate, Arguments.Resolved>()
        val applicationOrdinals = ConcurrentHashMap<FieldCoordinate, AtomicInteger>()
        val world =
            TestWorld.fromSDL(
            schemaSDL = schemaSDL,
            nodeResolvers = { canonicalSchema ->
                nodeValues.map { (typeName, plan) ->
                    val type = canonicalSchema.requireType(typeName) as ViaductSchema.Object
                    val materialize: (String) -> ResolverOutputData? = { id ->
                        when (plan) {
                            is ObjectPlan ->
                                plan.materializeObject(
                                    schema = canonicalSchema,
                                    inputId = id,
                                    generatedHashSeed = stableGeneratedHash(typeName, id),
                                )
                            is RootFieldReferencePlan -> plan.materializeReference(canonicalSchema)
                            else -> error("Node resolver $typeName has unsupported value plan $plan")
                        }
                    }
                    type to
                        if (selectiveNodeResolvers) {
                            selectionAwareNodeResolverOf { id, demand ->
                                selectiveNodeApplicationLog.record(typeName, id, demand)
                                materialize(id)
                            }
                        } else {
                            nodeResolverOf(materialize)
                        }
                }.toMap()
            },
            fieldResolvers = { canonicalSchema ->
                val sourceSchema = SourceSchemaAdapter(canonicalSchema)
                fieldValues.map { (coordinate, plan) ->
                    val field =
                        sourceSchema.field(
                            coordinate.typeName,
                            coordinate.fieldName,
                        )
                    val constant =
                        plan.materialize(
                            canonicalSchema,
                            sourceSchema.typeExpr(field),
                        )
                    val program = resolverPrograms.getValue(coordinate)
                    val objectFragment =
                        objectFragments
                            .getValue(coordinate)
                            .materialize(
                                canonicalSchema,
                                field as ViaductSchema.ObjectField,
                            )
                    val queryFragment =
                        queryFragments
                            .getValue(coordinate)
                            .materialize(
                                canonicalSchema,
                                field,
                            )
                    val definition =
                        fieldResolverOf(
                            objectFragment = objectFragment,
                            queryFragment = queryFragment,
                            function = { input, queryValue, arguments ->
                                field.args
                                    .filter { argument -> argument.hasDefault }
                                    .forEach { argument ->
                                        require(argument.name in arguments.fieldValues) {
                                            "Concrete default ${coordinate.typeName}/" +
                                                "${coordinate.fieldName}(${argument.name}) " +
                                                "was not applied"
                                        }
                                    }
                                val effectiveInput =
                                    if (
                                        resolverProgramMutation ==
                                        ResolverProgramMutation.CACHE_FIRST_INPUT
                                    ) {
                                        firstInputs.computeIfAbsent(coordinate) { input }
                                    } else {
                                        input
                                    }
                                val effectiveArguments =
                                    if (
                                        resolverProgramMutation ==
                                        ResolverProgramMutation.CACHE_FIRST_ARGUMENTS
                                    ) {
                                        firstArguments.computeIfAbsent(coordinate) { arguments }
                                    } else {
                                        arguments
                                    }
                                val ordinal: Int? =
                                    if (
                                        resolverProgramMutation ==
                                        ResolverProgramMutation.APPLICATION_ORDINAL_CONTAMINATION
                                    ) {
                                        applicationOrdinals
                                            .computeIfAbsent(coordinate) { AtomicInteger() }
                                            .getAndIncrement()
                                    } else {
                                        null
                                    }
                                val generatedHashSeed =
                                    stableGeneratedHash(
                                        effectiveInput.resolutionFingerprint().value,
                                        queryValue.resolutionFingerprint().value,
                                        effectiveArguments
                                            .resolutionFingerprint(field)
                                            .value,
                                    )
                                when (program) {
                                    ResolverProgramKind.CONSTANT -> constant
                                    else ->
                                        if (
                                            !field.type.isList &&
                                            field.type.baseTypeDef is ViaductSchema.SimpleTypeDef
                                        ) {
                                            sensitiveScalar(
                                                scalar =
                                                    ScalarKind.entries.single {
                                                        it.graphQLName ==
                                                            field.type.baseTypeDef.name
                                                    },
                                                input = effectiveInput,
                                                arguments = effectiveArguments,
                                                argumentField = field,
                                                applicationOrdinal = ordinal,
                                            )
                                        } else {
                                            plan.materialize(
                                                schema = canonicalSchema,
                                                typeExpr = sourceSchema.typeExpr(field),
                                                generatedHashSeed = generatedHashSeed,
                                            )
                                    }
                                }
                            },
                        )
                    val providerPlans =
                        variableProviders
                            .filterIsInstance<FromProviderVariableProviderPlan>()
                            .filter { provider -> provider.owner == coordinate }
                    val parsedProviderNames =
                        (objectFragment.subselections.usedVariables() +
                            queryFragment.subselections.usedVariables())
                            .mapTo(linkedSetOf(), Arguments.Variable::variableName)
                    require(providerPlans.all { provider -> provider.variableName in parsedProviderNames }) {
                        "Parsed fragments lost provider variables for $coordinate: " +
                            "planned=${providerPlans.map { it.variableName }} parsed=$parsedProviderNames"
                    }
                    field to
                        if (providerPlans.isEmpty()) {
                            definition
                        } else {
                            definition.withVariablesProvider(
                                providerPlans.mapTo(linkedSetOf()) { provider ->
                                    provider.variableName
                                },
                            ) { arguments ->
                                providerPlans.associate { provider ->
                                    provider.variableName to provider.value(arguments, field)
                                }
                            }
                        }
                }.toMap()
            },
            variableProviders = { canonicalSchema ->
                val sourceSchema = SourceSchemaAdapter(canonicalSchema)
                variableProviders.mapNotNull { provider ->
                    if (provider is FromProviderVariableProviderPlan) return@mapNotNull null
                    val field =
                        sourceSchema.field(
                            provider.owner.typeName,
                            provider.owner.fieldName,
                        ) as ViaductSchema.ObjectField
                    Arguments.Variable.of(
                        field,
                        provider.variableName,
                    ) to
                        when (provider) {
                            is FromArgumentVariableProviderPlan ->
                                canonicalSchema.fromArgument(
                                    field = field,
                                    path = provider.argumentPath,
                                )
                            is FromFieldVariableProviderPlan ->
                                when (provider.providerFragment) {
                                    ProviderFragment.OBJECT ->
                                        canonicalSchema.fromObjectField(
                                            objectFragmentSource =
                                                objectFragmentSources.getValue(provider.owner),
                                            responsePath = provider.responsePath(),
                                            variableField = field,
                                        )
                                    ProviderFragment.QUERY ->
                                        canonicalSchema.fromQueryField(
                                            queryFragmentSource =
                                                queryFragmentSources.getValue(provider.owner),
                                            responsePath = provider.responsePath(),
                                            variableField = field,
                                        )
                                }
                            is FromProviderVariableProviderPlan ->
                                error("FromProvider plan was not filtered")
                        }
                }.toMap()
            },
        )
        objectFragmentSources.values
            .filter(String::isNotEmpty)
            .forEach(world::selectionsFrom)
        queryFragmentSources.values
            .filter(String::isNotEmpty)
            .forEach(world::selectionsFrom)
        variableProviderSources.values.forEach(world::selectionsFrom)
        return world
    }

    override fun toString(): String =
        buildString {
            appendLine("field resolvers:")
            fieldResolverCoordinates.sortedBy(FieldCoordinate::toString).forEach { site ->
                appendLine("  $site OSS=${outputSelectionSets[site.toString()].orEmpty().sorted()}")
                val fragment = objectFragmentSources.getValue(site)
                if (fragment.isNotEmpty()) appendLine(fragment.prependIndent("    "))
                val queryFragment = queryFragmentSources.getValue(site)
                if (queryFragment.isNotEmpty()) {
                    appendLine("    query fragment:")
                    appendLine(queryFragment.prependIndent("      "))
                }
            }
            appendLine("variables:")
            variableProviders.sortedBy(VariableProviderPlan::variableName).forEach { provider ->
                when (provider) {
                    is FromArgumentVariableProviderPlan ->
                        appendLine(
                            "  \$${provider.variableName} owner=${provider.owner} " +
                                "fromArgument=${provider.argumentPath.joinToString(".")}",
                        )
                    is FromProviderVariableProviderPlan ->
                        appendLine(
                            "  \$${provider.variableName} owner=${provider.owner} fromProvider",
                        )
                    is FromFieldVariableProviderPlan -> {
                        appendLine(
                            "  \$${provider.variableName} owner=${provider.owner}",
                        )
                        appendLine(provider.source().prependIndent("    "))
                    }
                }
            }
            appendLine("node resolvers:")
            nodeResolverTypes.sorted().forEach { site ->
                appendLine("  $site OSS=${outputSelectionSets[site].orEmpty().sorted()}")
            }
        }.trimEnd()
}

fun ArbitrarySchema.registry(config: Config = Config.default): Arb<ArbitraryRegistry> {
    val generatedSchema = this
    return arbitrary { random ->
        RegistryGenerator(generatedSchema, config, random).generate()
    }
}

private class RegistryGenerator(
    private val schema: ArbitrarySchema,
    private val config: Config,
    private val random: RandomSource,
) {
    private lateinit var fieldSites: Set<FieldCoordinate>
    private lateinit var nodeSites: Set<String>

    fun generate(): ArbitraryRegistry {
        val rootFieldReferenceFamily = schema.rootFieldReferenceFamily
        val forcedRootFieldReferenceSites =
            rootFieldReferenceFamily?.let { family ->
                family.targetCoordinates +
                    family.namespaceCoordinates +
                    family.consumerCoordinate +
                    family.consumerValueCoordinates +
                    family.fallbackCoordinate +
                    family.extensionCoordinates
            }.orEmpty()
        nodeSites =
            if (config[NodeResolversEnabled]) {
                schema.objects
                    .filter(ObjectDefinition::implementsNode)
                    .mapTo(linkedSetOf(), ObjectDefinition::name)
            } else {
                emptySet()
            }
        fieldSites =
            schema.allObjects
                .flatMap(ObjectDefinition::fields)
                .filter { field ->
                    field.ownerName != GENERATED_HASH_TYPE &&
                        !field.isGeneratedHashField() &&
                        !field.isParentField &&
                        !field.isGeneratedPassiveAbstractOutput() &&
                        (
                            field.ownerName == "Query" ||
                                field.coordinate in forcedRootFieldReferenceSites ||
                                field.arguments.isNotEmpty() ||
                                field.isGeneratedParentSpineResolver() ||
                                chance(config[ExplicitFieldResolverWeight])
                        )
                }.map(FieldDefinitionSpec::coordinate)
                .shuffled(random)
                .withGeneratedRandomParentResolverOrder(config[RandomParentFieldsEnabled])
                .withGeneratedParentResultAfterAncestor(config[ParentFieldsEnabled])
                .toCollection(linkedSetOf())

        val generatedFieldValues =
            fieldSites.associateWith { coordinate ->
                val field = field(coordinate)
                plan(field.type, "${coordinate.typeName}.${coordinate.fieldName}")
            }
        val baseFieldValues =
            rootFieldReferenceFamily?.let { family ->
                generatedFieldValues.toMutableMap().apply {
                    this[family.targets.getValue("zero").path.last()] =
                        RootFieldReferencePlan(family.targets.getValue("one"))
                    this[family.targets.getValue("one").path.last()] =
                        RootFieldReferencePlan(family.targets.getValue("tail"))
                    this[family.consumerCoordinate] = fixedRootFieldReferenceConsumer(family)
                }.toMap()
            } ?: generatedFieldValues
        val baseNodeValues: Map<String, ValuePlan> =
            nodeSites
                .associateWith<String, ValuePlan> { typeName ->
                    objectPlan(
                        typeName = typeName,
                        path = typeName,
                        nodeResolverRoot = true,
                    )
                }.toMutableMap()
                .apply {
                    rootFieldReferenceFamily?.nodeResolverType?.let { typeName ->
                        this[typeName] =
                            RootFieldReferencePlan(
                                rootFieldReferenceFamily.targets.getValue("node"),
                            )
                    }
                }
        val ranks = fieldSites.withIndex().associate { (rank, site) -> site to rank }
        val variableProviders = mutableListOf<VariableProviderPlan>()
        val resolverFragments =
            fieldSites.associateWith { site ->
                resolverFragmentPlans(site, ranks, variableProviders)
            }
        val objectFragments =
            resolverFragments.mapValues { (_, fragments) -> fragments.objectFragment }
        val queryFragments =
            resolverFragments.mapValues { (_, fragments) -> fragments.queryFragment }
        val resolverPrograms =
            fieldSites.associateWith { site ->
                val field = field(site)
                val valuePlan = baseFieldValues.getValue(site)
                val scalarOutput =
                    !field.type.list &&
                        ScalarKind.entries.any { it.graphQLName == field.type.namedType }
                val structuredOutput = valuePlan.containsGeneratedHash()
                val supportsSensitiveOutput = scalarOutput || structuredOutput
                val inputSensitive =
                    supportsSensitiveOutput &&
                        objectFragments.getValue(site).selections.isNotEmpty()
                val argumentSensitive =
                    supportsSensitiveOutput && field.arguments.isNotEmpty()
                when {
                    site.isGeneratedSometimesPassiveParentResolver() ->
                        ResolverProgramKind.CONSTANT
                    inputSensitive && argumentSensitive ->
                        ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                    inputSensitive -> ResolverProgramKind.INPUT_SENSITIVE
                    argumentSensitive -> ResolverProgramKind.ARGUMENT_SENSITIVE
                    else -> ResolverProgramKind.CONSTANT
                }
            }
        var sometimesPassiveFieldCount = 0
        val fieldValues =
            baseFieldValues.mapValues { (coordinate, value) ->
                val rootDecorated =
                    if (
                        rootFieldReferenceFamily == null ||
                        coordinate in rootFieldReferenceFamily.targetCoordinates ||
                        coordinate == rootFieldReferenceFamily.consumerCoordinate
                    ) {
                        value
                    } else {
                        value.withRootFieldReferences(
                            expectedType = field(coordinate).type,
                            family = rootFieldReferenceFamily,
                        )
                    }
                rootDecorated.withSometimesPassiveFields(
                    baseFieldValues = baseFieldValues,
                    resolverPrograms = resolverPrograms,
                    onInsertion = { sometimesPassiveFieldCount += 1 },
                )
            }
        val nodeValues =
            baseNodeValues.mapValues { (_, value) ->
                value.withSometimesPassiveFields(
                    baseFieldValues = baseFieldValues,
                    resolverPrograms = resolverPrograms,
                    onInsertion = { sometimesPassiveFieldCount += 1 },
                )
            }
        val oss =
            buildMap {
                fieldValues.forEach { (coordinate, value) ->
                    put(coordinate.toString(), value.selectedPaths())
                }
                nodeValues.forEach { (typeName, value) ->
                    put(typeName, value.selectedPaths())
                }
            }
        val objectFieldFeatures =
            variableProviders.fromField(ProviderFragment.OBJECT).features(fieldSites)
        val queryFieldFeatures =
            variableProviders.fromField(ProviderFragment.QUERY).features(fieldSites)
        val parentDemandOwnerFields =
            objectFragments
                .mapValues { (_, fragment) -> fragment.maximumParentSelectionDepth() }
                .filterValues { depth -> depth > 0 }
        return ArbitraryRegistry(
            fieldResolverCoordinates = fieldSites,
            nodeResolverTypes = nodeSites,
            outputSelectionSets = oss,
            objectFragmentSources =
                objectFragments.mapValues { (_, fragment) -> fragment.source() },
            queryFragmentSources =
                queryFragments.mapValues { (_, fragment) -> fragment.source() },
            variableProviderSources =
                variableProviders
                        .mapNotNull { provider ->
                            when (provider) {
                                is FromFieldVariableProviderPlan ->
                                    provider.variableName to provider.source()
                                is FromArgumentVariableProviderPlan,
                                is FromProviderVariableProviderPlan,
                                -> null
                        }
                    }.toMap(),
            fieldValues = fieldValues,
            nodeValues = nodeValues,
            objectFragments = objectFragments,
            queryFragments = queryFragments,
            variableProviders = variableProviders,
            resolverPrograms = resolverPrograms,
            parentDemandOwnerFields = parentDemandOwnerFields,
            schemaRootFieldReferenceFamily = rootFieldReferenceFamily,
            features =
                RegistryFeatures(
                    inputSensitiveResolvers =
                        resolverPrograms.count {
                            it.value == ResolverProgramKind.INPUT_SENSITIVE ||
                                it.value == ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                        },
                    argumentSensitiveResolvers =
                        resolverPrograms.count {
                            it.value == ResolverProgramKind.ARGUMENT_SENSITIVE ||
                                it.value == ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                        },
                    inputAndArgumentSensitiveResolvers =
                        resolverPrograms.count {
                            it.value == ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                        },
                    sometimesPassiveFieldCount = sometimesPassiveFieldCount,
                    resolverErrorArgumentCount =
                        objectFragments.values.sumOf(FragmentPlan::errorArgumentCount) +
                            queryFragments.values.sumOf(FragmentPlan::errorArgumentCount),
                    variableCount = variableProviders.size,
                    fromArgumentVariableCount =
                        variableProviders.count {
                            it is FromArgumentVariableProviderPlan
                        },
                    fromArgumentNestedPathVariableCount =
                        variableProviders
                            .filterIsInstance<FromArgumentVariableProviderPlan>()
                            .count { provider -> provider.inputPath.isNotEmpty() },
                    fromArgumentNullableTraversalVariableCount =
                        variableProviders
                            .filterIsInstance<FromArgumentVariableProviderPlan>()
                            .count(FromArgumentVariableProviderPlan::nullableTraversal),
                    fromProviderVariableCount =
                        variableProviders.count {
                            it is FromProviderVariableProviderPlan
                        },
                    fromProviderArgumentSensitiveVariableCount =
                        variableProviders
                            .filterIsInstance<FromProviderVariableProviderPlan>()
                            .count(FromProviderVariableProviderPlan::argumentSensitive),
                    fromObjectFieldVariableCount = objectFieldFeatures.variableCount,
                    literalVariableConvergenceCount =
                        variableProviders.count(VariableProviderPlan::literalConvergence),
                    fromObjectFieldLiteralVariableConvergenceCount =
                        objectFieldFeatures.literalVariableConvergenceCount,
                    sameFragmentVariableReuseCount =
                        variableProviders.count { provider ->
                            val objectUses =
                                objectFragments[provider.owner]
                                    ?.variableUseCount(provider.variableName)
                                    ?: 0
                            val queryUses =
                                queryFragments[provider.owner]
                                    ?.variableUseCount(provider.variableName)
                                    ?: 0
                            objectUses > 1 || queryUses > 1
                        },
                    singletonCoercionVariableCount =
                        variableProviders
                            .filterIsInstance<FromArgumentVariableProviderPlan>()
                            .count { provider ->
                                !provider.listValue &&
                                    (
                                        objectFragments[provider.owner]
                                            ?.variableTargets(provider.variableName)
                                            .orEmpty() +
                                            queryFragments[provider.owner]
                                                ?.variableTargets(provider.variableName)
                                                .orEmpty()
                                    ).any { target -> target is ListVariableTarget }
                            },
                    passiveTopLevelFromObjectFieldVariableUseCount =
                        objectFieldFeatures.passiveTopLevelVariableUseCount,
                    fromObjectFieldProviderArgumentVariableCount =
                        objectFieldFeatures.providerArgumentVariableCount,
                    maximumFromObjectFieldPathLength = objectFieldFeatures.maximumPathLength,
                    maximumFromObjectFieldVariableUseDepth =
                        objectFieldFeatures.maximumVariableUseDepth,
                    maximumVariablesPerOwner =
                        variableProviders
                            .groupingBy(VariableProviderPlan::owner)
                            .eachCount()
                            .values
                            .maxOrNull()
                            ?: 0,
                    hasNestedInputVariable =
                        variableProviders.any(VariableProviderPlan::nestedInput),
                    hasListVariable = variableProviders.any(VariableProviderPlan::listValue),
                    hasNullableProvider = variableProviders.any(VariableProviderPlan::nullable),
                    hasAbstractProviderPath = objectFieldFeatures.hasAbstractProviderPath,
                    hasAbstractResolverFragment =
                        (objectFragments.values + queryFragments.values).any { fragment ->
                            fragment.selections.any { selection ->
                                selection.hasAbstractPath(fragment.ownerName)
                            }
                        },
                    queryFragmentCount =
                        queryFragments.count { (_, fragment) ->
                            fragment.selections.isNotEmpty()
                        },
                    fromQueryFieldVariableCount = queryFieldFeatures.variableCount,
                    maximumFromQueryFieldPathLength = queryFieldFeatures.maximumPathLength,
                    maximumFromQueryFieldVariableUseDepth =
                        queryFieldFeatures.maximumVariableUseDepth,
                    maximumParentSelectionDepth =
                        parentDemandOwnerFields.values.maxOrNull() ?: 0,
                    resolverOutputParentFieldCount =
                        (fieldValues.values + nodeValues.values)
                            .sumOf { value -> value.parentFieldCount() },
                    generatedRootFieldReferenceCount =
                        (fieldValues.values + nodeValues.values)
                            .sumOf { value -> value.countRootFieldReferences() },
                    rootFieldReferenceTargetCount =
                        rootFieldReferenceFamily?.targetCoordinates?.size ?: 0,
                    rootFieldReferenceFallbackCount =
                        if (rootFieldReferenceFamily == null) 0 else 1,
                    generatedNodeRootFieldReferenceCount =
                        nodeValues.values.sumOf { value -> value.countRootFieldReferences() },
                ),
        )
    }

    private fun ValuePlan.withSometimesPassiveFields(
        baseFieldValues: Map<FieldCoordinate, ValuePlan>,
        resolverPrograms: Map<FieldCoordinate, ResolverProgramKind>,
        onInsertion: () -> Unit,
    ): ValuePlan {
        val weight = config[SometimesPassiveFieldWeight]
        if (weight == 0.0) return this
        return when (this) {
            is ListPlan ->
                copy(
                    elements =
                        elements.map { element ->
                            element.withSometimesPassiveFields(
                                baseFieldValues,
                                resolverPrograms,
                                onInsertion,
                            )
                        },
                )

            is ObjectPlan -> {
                val recursivelyDecoratedFields =
                    fields.mapValues { (_, value) ->
                        value.withSometimesPassiveFields(
                            baseFieldValues,
                            resolverPrograms,
                            onInsertion,
                        )
                    }
                val insertedFields =
                    fieldSites
                        .asSequence()
                        .filter { coordinate -> coordinate.typeName == typeName }
                        .filter { coordinate -> coordinate !in fields }
                        .filter { coordinate -> field(coordinate).arguments.isEmpty() }
                        .filter { coordinate ->
                            resolverPrograms.getValue(coordinate) ==
                                ResolverProgramKind.CONSTANT
                        }
                        .filter { coordinate ->
                            !baseFieldValues.getValue(coordinate).containsGeneratedHash()
                        }
                        .filter { chance(weight) }
                        .associateWith { coordinate ->
                            onInsertion()
                            baseFieldValues.getValue(coordinate)
                        }
                copy(fields = recursivelyDecoratedFields + insertedFields)
            }

            else -> this
        }
    }

    private fun fixedRootFieldReferenceConsumer(
        family: RootFieldReferenceFamily,
    ): ObjectPlan {
        fun consumerField(name: String): FieldCoordinate =
            family.consumerValueCoordinates.single { coordinate ->
                coordinate.fieldName == name
            }
        return ObjectPlan(
            typeName = GENERATED_ROOT_REFERENCE_CONSUMER,
            fields =
                linkedMapOf(
                    consumerField("objectValue") to
                        RootFieldReferencePlan(family.targets.getValue("zero")),
                    consumerField("concreteValue") to
                        RootFieldReferencePlan(family.targets.getValue("object")),
                    consumerField("unionValue") to
                        RootFieldReferencePlan(family.targets.getValue("four")),
                    consumerField("enumValue") to
                        RootFieldReferencePlan(family.targets.getValue("enum")),
                    consumerField("scalarValue") to
                        RootFieldReferencePlan(family.targets.getValue("scalar")),
                    consumerField("listValue") to
                        ListPlan(
                            listOf(
                                RootFieldReferencePlan(family.targets.getValue("one")),
                                objectPlan(
                                    typeName = GENERATED_ROOT_REFERENCE_PAIR_A,
                                    path = "fixedRootReferencePassiveListElement",
                                ),
                            ),
                        ),
                    FieldCoordinate(
                        GENERATED_ROOT_REFERENCE_CONSUMER,
                        GENERATED_HASH_FIELD,
                    ) to GeneratedHashPlan(GENERATED_ROOT_REFERENCE_CONSUMER.hashCode()),
                ),
        )
    }

    private fun ValuePlan.withRootFieldReferences(
        expectedType: OutputTypeSpec,
        family: RootFieldReferenceFamily,
    ): ValuePlan {
        val compatibleTargets = compatibleRootFieldReferenceTargets(expectedType, family)
        if (
            !expectedType.list &&
            compatibleTargets.isNotEmpty() &&
            chance(config[RootFieldReferenceWeight])
        ) {
            return RootFieldReferencePlan(Arb.element(compatibleTargets).next(random))
        }
        return when (this) {
            is ListPlan -> {
                val elementType = expectedType.elementType()
                copy(
                    elements =
                        elements.map { element ->
                            element.withRootFieldReferences(elementType, family)
                        },
                )
            }
            is ObjectPlan -> {
                val decorated =
                    fields.mapValues { (coordinate, value) ->
                        fieldOrNull(coordinate)?.let { field ->
                            value.withRootFieldReferences(
                                expectedType = field.type,
                                family = family,
                            )
                        } ?: value
                    }.toMutableMap()
                fieldSites
                    .asSequence()
                    .filter { coordinate -> coordinate.typeName == typeName }
                    .filterNot(decorated::containsKey)
                    .map { coordinate -> coordinate to field(coordinate) }
                    .filter { (_, field) ->
                        field.arguments.isEmpty() &&
                            !field.isGeneratedHashField() &&
                            !field.isParentField
                    }.forEach { (coordinate, field) ->
                        val targets = compatibleRootFieldReferenceTargets(field.type, family)
                        if (
                            targets.isNotEmpty() &&
                            chance(config[RootFieldReferenceWeight])
                        ) {
                            decorated[coordinate] =
                                RootFieldReferencePlan(Arb.element(targets).next(random))
                        }
                    }
                copy(fields = decorated)
            }
            else -> this
        }
    }

    private fun compatibleRootFieldReferenceTargets(
        expectedType: OutputTypeSpec,
        family: RootFieldReferenceFamily,
    ): List<RootFieldReferenceTargetSpec> {
        if (expectedType.list) return emptyList()
        return family.targets
            .filterKeys { key -> key != "node" }
            .values
            .filter { target ->
                val targetType = field(target.path.last()).type
                when {
                    targetType.list -> false
                    schema.isComposite(expectedType.namedType) &&
                        schema.isComposite(targetType.namedType) ->
                        schema
                            .possibleObjects(targetType.namedType)
                            .map(ObjectDefinition::name)
                            .all(
                                schema
                                    .possibleObjects(expectedType.namedType)
                                    .map(ObjectDefinition::name)
                                    .toSet()::contains,
                            )
                    else -> targetType.namedType == expectedType.namedType
                }
            }
    }

    private fun resolverFragmentPlans(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
    ): ResolverFragmentPlans {
        schema.rootFieldReferenceFamily
            ?.takeIf { family -> consumer in family.namespaceCoordinates }
            ?.let {
                return ResolverFragmentPlans(
                    objectFragment = FragmentPlan(consumer.typeName, emptyList()),
                    queryFragment = FragmentPlan("Query", emptyList()),
                )
            }
        schema.rootFieldReferenceFamily
            ?.takeIf { family -> consumer in family.targetCoordinates }
            ?.let { family ->
                return rootFieldReferenceTargetFragments(consumer, family, variableProviders)
            }
        if (consumer.isGeneratedParentResult()) {
            return ResolverFragmentPlans(
                objectFragment = generatedGreatGrandparentFragment(),
                queryFragment = FragmentPlan("Query", emptyList()),
            )
        }
        val preferredObjectFields =
            variableProviders
                .mapTo(linkedSetOf(), VariableProviderPlan::owner)
                .filterTo(linkedSetOf()) { owner ->
                    owner.typeName == consumer.typeName
                }
        val fragments =
            ResolverFragmentPlans(
                objectFragment =
                    fragmentPlan(
                        ownerName = consumer.typeName,
                        consumer = consumer,
                        ranks = ranks,
                        enabled = config[ResolverFragmentsEnabled],
                        weight = config[ResolverFragmentWeight],
                        preferredTopLevelFields = preferredObjectFields,
                    ),
                queryFragment =
                    fragmentPlan(
                        ownerName = "Query",
                        consumer = consumer,
                        ranks = ranks,
                        enabled = config[ResolverQueryFragmentsEnabled],
                        weight =
                            if (consumer.isGeneratedRandomParentDiagonalResolver()) {
                                1.0
                            } else {
                                config[ResolverQueryFragmentWeight]
                            },
                    ),
            )
                .withTopLevelRandomParentDemand(consumer, ranks)
                .withFromArgumentVariableProvider(consumer, ranks, variableProviders)
                .withFromProviderVariableProvider(consumer, ranks, variableProviders)
        return ProviderFragment.entries.fold(fragments) { result, providerFragment ->
            result.withFromFieldVariableProvider(
                consumer,
                ranks,
                variableProviders,
                providerFragment,
            )
        }
    }

    private fun rootFieldReferenceTargetFragments(
        consumer: FieldCoordinate,
        family: RootFieldReferenceFamily,
        variableProviders: MutableList<VariableProviderPlan>,
    ): ResolverFragmentPlans {
        fun intValue(value: InputValuePlan): FragmentSelectionPlan =
            FragmentSelectionPlan(
                fieldName = GENERATED_ROOT_REFERENCE_NAMESPACE_FIELD,
                arguments = emptyMap(),
                subselections =
                    listOf(
                        FragmentSelectionPlan(
                            fieldName = "level2",
                            arguments = emptyMap(),
                            subselections =
                                listOf(
                                    FragmentSelectionPlan(
                                        fieldName = "level3",
                                        arguments = emptyMap(),
                                        subselections =
                                            listOf(
                                                FragmentSelectionPlan(
                                                    fieldName = "scalarValue",
                                                    arguments = mapOf("value" to value),
                                                    subselections = emptyList(),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val emptyObject = FragmentPlan(consumer.typeName, emptyList())
        if (!config[ResolverQueryFragmentsEnabled]) {
            return ResolverFragmentPlans(
                objectFragment = emptyObject,
                queryFragment = FragmentPlan("Query", emptyList()),
            )
        }
        return when (consumer) {
            family.targets.getValue("zero").path.last() ->
                ResolverFragmentPlans(
                    objectFragment = emptyObject,
                    queryFragment =
                        FragmentPlan(
                            "Query",
                            listOf(
                                intValue(
                                    InputLiteralPlan(
                                        ScalarInputTypeSpec(ScalarKind.INT, nullable = false),
                                        3,
                                    ),
                                ),
                            ),
                        ),
                )
            family.targets.getValue("one").path.last() -> {
                val variableName = "rootReferenceArgument"
                variableProviders +=
                    FromArgumentVariableProviderPlan(
                        owner = consumer,
                        variableName = variableName,
                        argumentName = "id",
                        nestedInput = false,
                        listValue = false,
                        nullable = false,
                        literalConvergence = false,
                    )
                ResolverFragmentPlans(
                    objectFragment = emptyObject,
                    queryFragment =
                        FragmentPlan(
                            "Query",
                            listOf(intValue(VariableInputPlan(variableName))),
                        ),
                )
            }
            family.targets.getValue("four").path.last() -> {
                val variableName = "rootReferenceQueryPath"
                val provider =
                    intValue(
                        InputLiteralPlan(
                            ScalarInputTypeSpec(ScalarKind.INT, nullable = false),
                            5,
                        ),
                    ).withResponseAliases(variableName)
                variableProviders +=
                    FromFieldVariableProviderPlan(
                        owner = consumer,
                        variableName = variableName,
                        providerFragment = ProviderFragment.QUERY,
                        selection = provider,
                        nestedInput = false,
                        listValue = false,
                        nullable = false,
                        abstractPath = false,
                        useDepth = 4,
                        topLevelUseField = family.namespaceCoordinates.single { coordinate ->
                            coordinate.typeName == "Query"
                        },
                        literalConvergence = false,
                    )
                ResolverFragmentPlans(
                    objectFragment = emptyObject,
                    queryFragment =
                        FragmentPlan(
                            "Query",
                            listOf(
                                provider,
                                intValue(VariableInputPlan(variableName)),
                            ),
                        ),
                )
            }
            else ->
                ResolverFragmentPlans(
                    objectFragment = emptyObject,
                    queryFragment = FragmentPlan("Query", emptyList()),
                )
        }
    }

    private fun ResolverFragmentPlans.withTopLevelRandomParentDemand(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
    ): ResolverFragmentPlans {
        val parentField =
            schema
                .fieldsOn(consumer.typeName)
                .singleOrNull(FieldDefinitionSpec::isParentField)
        val sometimesPassiveParentWitness =
            consumer.isGeneratedSometimesPassiveParentResolver()
        if (
            !config[RandomParentFieldsEnabled] ||
            !consumer.typeName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX) ||
            parentField == null ||
            (
                !sometimesPassiveParentWitness &&
                    (
                        consumer.fieldName != "value0" ||
                            !chance(RANDOM_PARENT_DIAGONAL_RESOLVER_WEIGHT)
                    )
            )
        ) {
            return this
        }
        val lowerRankedParentResolver =
            schema
                .possibleObjects(parentField.type.namedType)
                .mapNotNull { possibleParent ->
                    FieldCoordinate(possibleParent.name, "value0").takeIf { candidate ->
                        candidate in fieldSites &&
                            ranks.getValue(candidate) < ranks.getValue(consumer)
                    }
                }.maxByOrNull(ranks::getValue)
        val nestedParentResolverSelection =
            lowerRankedParentResolver?.let { candidate ->
                val candidateField = field(candidate)
                FragmentSelectionPlan(
                    fieldName = candidate.fieldName,
                    arguments =
                        candidateField.arguments.associate { argument ->
                            argument.name to inputLiteral(argument.type)
                        },
                    subselections = emptyList(),
                    typeCondition =
                        candidate.typeName.takeUnless { typeName ->
                            typeName == parentField.type.namedType
                        },
                )
            }
        return copy(
            objectFragment =
                objectFragment.copy(
                    selections =
                        objectFragment.selections +
                            FragmentSelectionPlan(
                                fieldName = GENERATED_PARENT_FIELD,
                                arguments = emptyMap(),
                                alias = "resolverParentCoverage",
                                subselections =
                                    listOf(
                                        FragmentSelectionPlan(
                                            fieldName = "__typename",
                                            arguments = emptyMap(),
                                            subselections = emptyList(),
                                        ),
                                    ) + listOfNotNull(nestedParentResolverSelection),
                            ),
                ),
        )
    }

    private fun fragmentPlan(
        ownerName: String,
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        enabled: Boolean,
        weight: Double,
        preferredTopLevelFields: Set<FieldCoordinate> = emptySet(),
    ): FragmentPlan {
        if (!enabled || !chance(weight)) {
            return FragmentPlan(ownerName, emptyList())
        }
        return FragmentPlan(
            ownerName = ownerName,
            selections =
                fragmentSelections(
                    ownerName = ownerName,
                    consumerRank = ranks.getValue(consumer),
                    ranks = ranks,
                    depth = 0,
                    preferredTopLevelFields = preferredTopLevelFields,
                    targetSelectionCount = resolverFragmentSelectionCount(),
                ),
        )
    }

    private fun fragmentSelections(
        ownerName: String,
        consumerRank: Int,
        ranks: Map<FieldCoordinate, Int>,
        depth: Int,
        preferredTopLevelFields: Set<FieldCoordinate> = emptySet(),
        targetSelectionCount: Int? = null,
    ): List<FragmentSelectionPlan> {
        if (depth >= config[ResolverFragmentDepth]) {
            return listOf(
                FragmentSelectionPlan(
                    fieldName = "__typename",
                    arguments = emptyMap(),
                    subselections = emptyList(),
                ),
            )
        }
        val directFields = schema.fieldsOn(ownerName)
        if (directFields.isEmpty() && schema.isComposite(ownerName)) {
            return schema
                .possibleObjects(ownerName)
                .shuffled(random)
                .take(2)
                .flatMap { concrete ->
                    fragmentSelections(
                        ownerName = concrete.name,
                        consumerRank = consumerRank,
                        ranks = ranks,
                        depth = depth,
                        targetSelectionCount = targetSelectionCount,
                    ).take(1)
                        .map { selection -> selection.copy(typeCondition = concrete.name) }
                }.ifEmpty {
                    listOf(
                        FragmentSelectionPlan(
                            fieldName = "__typename",
                            arguments = emptyMap(),
                            subselections = emptyList(),
                        ),
                    )
                }
        }
        val candidates =
            directFields.filter { field ->
                !field.isGeneratedHashField() &&
                    field.hasOnlyLowerRankedResolverDependencies(consumerRank, ranks)
            }
        if (candidates.isEmpty()) {
            return listOf(
                FragmentSelectionPlan(
                    fieldName = "__typename",
                    arguments = emptyMap(),
                    subselections = emptyList(),
                ),
            )
        }
        val untargetedSelectionCount =
            if (targetSelectionCount == null) {
                Arb.int(1..minOf(2, candidates.size)).next(random)
            } else {
                null
            }
        val preferredField =
            candidates
                .filter { field ->
                    field.coordinate in preferredTopLevelFields &&
                        field.arguments.isNotEmpty()
                }.shuffled(random)
                .firstOrNull()
                ?.takeIf {
                    depth == 0 &&
                        chance(config[ResolverFromFieldVariableOwnerUseWeight])
                }
        val preferredArgumentField =
            if (config[ResolverFragmentArgumentFieldWeight] > 0.0) {
                candidates
                    .filter { field ->
                        field != preferredField && field.arguments.isNotEmpty()
                    }.shuffled(random)
                    .firstOrNull()
                    ?.takeIf {
                        chance(config[ResolverFragmentArgumentFieldWeight])
                    }
            } else {
                null
            }
        val preferredFields =
            listOfNotNull(preferredField, preferredArgumentField)
        val selectedFields =
            if (targetSelectionCount == null) {
                val count = requireNotNull(untargetedSelectionCount)
                preferredFields.take(count) +
                    candidates
                        .filterNot { field -> field in preferredFields }
                        .shuffled(random)
                        .take((count - preferredFields.size).coerceAtLeast(0))
            } else {
                targetedFragmentFields(
                    candidates = candidates,
                    preferredFields = preferredFields,
                    targetSelectionCount = targetSelectionCount,
                )
            }
        if (selectedFields.isEmpty()) {
            return listOf(
                FragmentSelectionPlan(
                    fieldName = "__typename",
                    arguments = emptyMap(),
                    subselections = emptyList(),
                ),
            )
        }
        val childSelectionCounts =
            targetedChildSelectionCounts(
                selectedFields = selectedFields,
                targetSelectionCount = targetSelectionCount,
            )
        return selectedFields
            .map { field ->
                FragmentSelectionPlan(
                    fieldName = field.name,
                    arguments =
                        field.arguments.associate { argument ->
                            val literal = inputLiteral(argument.type)
                            argument.name to
                                if (chance(config[ResolverArgumentErrorWeight])) {
                                    ErrorInputPlan(literal)
                                } else {
                                    literal
                                }
                        },
                    subselections =
                        field.type.namedType
                            .takeIf(schema::isComposite)
                            ?.let { outputType ->
                            if (
                                field.ownerName != "Query" &&
                                schema.allObjects.none { objectType ->
                                    objectType.name == outputType
                                } &&
                                schema.possibleObjects(outputType).size > 1
                            ) {
                                schema
                                    .possibleObjects(outputType)
                                    .shuffled(random)
                                    .take(2)
                                    .map { concrete ->
                                        FragmentSelectionPlan(
                                            fieldName = GENERATED_HASH_FIELD,
                                            arguments = emptyMap(),
                                            subselections =
                                                listOf(
                                                    FragmentSelectionPlan(
                                                        fieldName = GENERATED_HASH_FIELD,
                                                        arguments = emptyMap(),
                                                        subselections = emptyList(),
                                                    ),
                                                ),
                                            typeCondition = concrete.name,
                                        )
                                    }
                            } else {
                                fragmentSelections(
                                    ownerName = outputType,
                                    consumerRank = consumerRank,
                                    ranks = ranks,
                                    depth = depth + 1,
                                    targetSelectionCount = childSelectionCounts[field],
                                )
                            }
                        }.orEmpty(),
                )
            }
    }

    private fun resolverFragmentSelectionCount(): Int? {
        val ordinary = config[ResolverFragmentSelectionCount]
        val longTail = config[ResolverFragmentLongTailSelectionCount]
        val selectedRange =
            if (
                longTail != 0..0 &&
                chance(config[ResolverFragmentLongTailWeight])
            ) {
                longTail
            } else {
                ordinary
            }
        return selectedRange
            .takeUnless { range -> range == 0..0 }
            ?.let { range -> Arb.int(range).next(random).coerceAtLeast(1) }
    }

    private fun targetedFragmentFields(
        candidates: List<FieldDefinitionSpec>,
        preferredFields: List<FieldDefinitionSpec>,
        targetSelectionCount: Int,
    ): List<FieldDefinitionSpec> {
        val ordered =
            (
                preferredFields +
                    candidates
                        .filterNot { field -> field in preferredFields }
                        .shuffled(random)
                        .sortedByDescending { field -> schema.isComposite(field.type.namedType) }
            ).distinct()
        val selected = mutableListOf<FieldDefinitionSpec>()
        var minimumSelectionCount = 0
        ordered.forEach { field ->
            if (selected.size == 2) return@forEach
            val fieldMinimum =
                if (schema.isComposite(field.type.namedType)) 2 else 1
            if (minimumSelectionCount + fieldMinimum <= targetSelectionCount) {
                selected += field
                minimumSelectionCount += fieldMinimum
            }
        }
        return selected
    }

    private fun targetedChildSelectionCounts(
        selectedFields: List<FieldDefinitionSpec>,
        targetSelectionCount: Int?,
    ): Map<FieldDefinitionSpec, Int> {
        if (targetSelectionCount == null) return emptyMap()
        val compositeFields =
            selectedFields.filter { field -> schema.isComposite(field.type.namedType) }
        if (compositeFields.isEmpty()) return emptyMap()
        val minimumSelectionCount = selectedFields.size + compositeFields.size
        var extras = (targetSelectionCount - minimumSelectionCount).coerceAtLeast(0)
        val childCounts = compositeFields.associateWith { 1 }.toMutableMap()
        while (extras > 0) {
            val field = compositeFields[Arb.int(compositeFields.indices).next(random)]
            childCounts[field] = childCounts.getValue(field) + 1
            extras -= 1
        }
        return childCounts
    }

    private fun ResolverFragmentPlans.withFromArgumentVariableProvider(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
    ): ResolverFragmentPlans {
        val directedParentCoverage = consumer.isGeneratedRandomParentDiagonalResolver()
        if (
            !config[ResolverFromArgumentVariablesEnabled] ||
            (!directedParentCoverage && !chance(config[ResolverVariableWeight]))
        ) {
            return this
        }
        val resolverArguments = field(consumer).arguments
        val variableCount =
            if (directedParentCoverage) 2 else Arb.int(config[ResolverVariableCount]).next(random)
        return (0 until variableCount).fold(this) { fragments, variableIndex ->
            val preferredLocation =
                if (variableIndex % 2 == 0) FragmentLocation.OBJECT else FragmentLocation.QUERY
            val candidates =
                fragments.argumentOccurrences()
                    .shuffled(random)
                    .filter { locatedOccurrence ->
                        locatedOccurrence.occurrence.existingVariableName == null
                    }
                    .mapNotNull { locatedOccurrence ->
                        val occurrence = locatedOccurrence.occurrence
                        occurrence.target.let { target ->
                            val sources =
                                resolverArguments
                                    .flatMap(::fromArgumentSources)
                                    .filter { source ->
                                        target.accepts(
                                            source.type,
                                            config[ResolverVariableSingletonCoercionEnabled],
                                        )
                                    }
                            val nestedSources =
                                sources.filter { source -> source.inputPath.isNotEmpty() }
                            val preferredSources =
                                if (
                                    nestedSources.isNotEmpty() &&
                                    chance(config[ResolverFromArgumentNestedPathWeight])
                                ) {
                                    nestedSources
                                } else {
                                    sources
                                }
                            preferredSources
                                .shuffled(random)
                                .firstOrNull()
                                ?.let { source ->
                                    FromArgumentCandidate(
                                        location = locatedOccurrence.location,
                                        occurrence = occurrence,
                                        source = source,
                                    )
                                }
                        }
                    }.let { candidates ->
                        if (directedParentCoverage) {
                            candidates.sortedBy { candidate ->
                                candidate.location != preferredLocation
                            }
                        } else {
                            candidates
                        }
                    }
            val convergenceCandidate =
                candidates.firstOrNull { candidate ->
                    fragments
                        .fragment(candidate.location)
                        .selectionAt(candidate.occurrence)
                        .subselections
                        .size >= 2
                }
            val literalConvergence =
                convergenceCandidate != null &&
                    chance(config[ResolverLiteralVariableConvergenceWeight])
            val candidate =
                if (literalConvergence) {
                    requireNotNull(convergenceCandidate)
                } else {
                    candidates.firstOrNull() ?: return@fold fragments
                }
            val sharedOccurrence =
                fragments
                    .argumentOccurrences()
                    .filter { locatedOccurrence ->
                        locatedOccurrence.occurrence.existingVariableName == null
                    }
                    .filterNot { locatedOccurrence ->
                        locatedOccurrence.location == candidate.location &&
                            (
                                literalConvergence ||
                                    locatedOccurrence.occurrence.selectionPath ==
                                    candidate.occurrence.selectionPath
                            )
                    }
                    .shuffled(random)
                    .firstOrNull { locatedOccurrence ->
                        locatedOccurrence.occurrence.target.accepts(
                            candidate.source.type,
                            config[ResolverVariableSingletonCoercionEnabled],
                        )
                    }
                    .takeUnless { directedParentCoverage }
            val variableName = "resolverArgVar${ranks.getValue(consumer)}_$variableIndex"
            variableProviders +=
                FromArgumentVariableProviderPlan(
                    owner = consumer,
                    variableName = variableName,
                    argumentName = candidate.source.argument.name,
                    inputPath = candidate.source.inputPath,
                    nullableTraversal = candidate.source.nullableTraversal,
                    nestedInput = candidate.occurrence.valuePath.isNotEmpty(),
                    listValue = candidate.source.type is ListInputTypeSpec,
                    nullable = candidate.source.type.nullable,
                    literalConvergence = literalConvergence,
                )
            fragments
                .replaceArgument(
                    location = candidate.location,
                    occurrence = candidate.occurrence,
                    variableName = variableName,
                    literalConvergence = literalConvergence,
                ).let { updated ->
                    sharedOccurrence?.let { shared ->
                        updated.replaceArgument(
                            location = shared.location,
                            occurrence = shared.occurrence,
                            variableName = variableName,
                            literalConvergence = false,
                        )
                    } ?: updated
                }
        }
    }

    private fun ResolverFragmentPlans.argumentOccurrences(): List<LocatedArgumentOccurrence> =
        objectFragment.argumentOccurrences().map { occurrence ->
            LocatedArgumentOccurrence(FragmentLocation.OBJECT, occurrence)
        } +
            queryFragment.argumentOccurrences().map { occurrence ->
                LocatedArgumentOccurrence(FragmentLocation.QUERY, occurrence)
            }

    private fun ResolverFragmentPlans.withFromProviderVariableProvider(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
    ): ResolverFragmentPlans {
        if (
            !config[ResolverFromProviderVariablesEnabled] ||
            !chance(config[ResolverVariableWeight])
        ) {
            return this
        }
        val variableCount = Arb.int(config[ResolverVariableCount]).next(random)
        return (0 until variableCount).fold(this) { fragments, variableIndex ->
            val candidate =
                fragments
                    .argumentOccurrences()
                    .shuffled(random)
                    .firstOrNull { locatedOccurrence ->
                        locatedOccurrence.occurrence.existingVariableName == null &&
                            fragments
                                .fragment(locatedOccurrence.location)
                                .selectionAt(locatedOccurrence.occurrence)
                                .arguments
                                .values
                                .none { argument -> argument is ErrorInputPlan } &&
                            !fragments
                                .fragment(locatedOccurrence.location)
                                .argumentOccursAcrossNodeBoundary(locatedOccurrence.occurrence)
                    } ?: return@fold fragments
            val variableName = "resolverProviderVar${ranks.getValue(consumer)}_$variableIndex"
            variableProviders +=
                FromProviderVariableProviderPlan(
                    owner = consumer,
                    variableName = variableName,
                    target = candidate.occurrence.target,
                    nestedInput = candidate.occurrence.valuePath.isNotEmpty(),
                    argumentSensitive = field(consumer).arguments.isNotEmpty(),
                )
            fragments.replaceArgument(
                location = candidate.location,
                occurrence = candidate.occurrence,
                variableName = variableName,
                literalConvergence = false,
            )
        }
    }

    private fun ResolverFragmentPlans.fragment(location: FragmentLocation): FragmentPlan =
        when (location) {
            FragmentLocation.OBJECT -> objectFragment
            FragmentLocation.QUERY -> queryFragment
        }

    private fun ResolverFragmentPlans.appendSelection(
        location: FragmentLocation,
        selection: FragmentSelectionPlan,
    ): ResolverFragmentPlans =
        when (location) {
            FragmentLocation.OBJECT ->
                copy(
                    objectFragment =
                        objectFragment.copy(selections = objectFragment.selections + selection),
                )
            FragmentLocation.QUERY ->
                copy(
                    queryFragment =
                        queryFragment.copy(selections = queryFragment.selections + selection),
                )
        }

    private fun ResolverFragmentPlans.replaceArgument(
        location: FragmentLocation,
        occurrence: ArgumentOccurrence,
        variableName: String,
        literalConvergence: Boolean,
    ): ResolverFragmentPlans {
        val fragment = fragment(location)
        val selections =
            if (literalConvergence) {
                fragment.selections.replaceArgumentWithLiteralConvergence(
                    selectionPath = occurrence.selectionPath,
                    argumentName = occurrence.argument.name,
                    valuePath = occurrence.valuePath,
                    variableName = variableName,
                    target = occurrence.target,
                )
            } else {
                fragment.selections.replaceArgument(
                    selectionPath = occurrence.selectionPath,
                    argumentName = occurrence.argument.name,
                    valuePath = occurrence.valuePath,
                    value = VariableInputPlan(variableName, occurrence.target),
                )
            }
        return when (location) {
            FragmentLocation.OBJECT -> copy(objectFragment = fragment.copy(selections = selections))
            FragmentLocation.QUERY -> copy(queryFragment = fragment.copy(selections = selections))
        }
    }

    private fun fromArgumentSources(
        argument: ArgumentDefinitionSpec,
    ): List<FromArgumentSource> =
        listOf(
            FromArgumentSource(
                argument = argument,
                inputPath = emptyList(),
                type = argument.type,
                nullableTraversal = false,
            ),
        ) + nestedFromArgumentSources(
            argument = argument,
            type = argument.type,
            inputPath = emptyList(),
            nullableTraversal = false,
            visitedInputObjects = emptySet(),
        )

    private fun nestedFromArgumentSources(
        argument: ArgumentDefinitionSpec,
        type: InputTypeSpec,
        inputPath: List<String>,
        nullableTraversal: Boolean,
        visitedInputObjects: Set<String>,
    ): List<FromArgumentSource> {
        val inputObject = type as? InputObjectInputTypeSpec ?: return emptyList()
        if (inputObject.name in visitedInputObjects) return emptyList()
        val traversesNullable = nullableTraversal || inputObject.nullable
        val definition =
            schema.inputObjects.single { candidate -> candidate.name == inputObject.name }
        return definition.fields.flatMap { field ->
            val path = inputPath + field.name
            val sourceType =
                field.type.withOuterNullability(
                    field.type.nullable || traversesNullable,
                )
            listOf(
                FromArgumentSource(
                    argument = argument,
                    inputPath = path,
                    type = sourceType,
                    nullableTraversal = traversesNullable,
                ),
            ) + nestedFromArgumentSources(
                argument = argument,
                type = field.type,
                inputPath = path,
                nullableTraversal = traversesNullable,
                visitedInputObjects = visitedInputObjects + inputObject.name,
            )
        }
    }

    private fun ResolverFragmentPlans.withFromFieldVariableProvider(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
        providerFragment: ProviderFragment,
    ): ResolverFragmentPlans {
        val directedParentCoverage = consumer.isGeneratedRandomParentDiagonalResolver()
        val providerLocation = providerFragment.location()
        val providerPlan = fragment(providerLocation)
        val providerOwnerName = providerPlan.ownerName
        val variablesEnabled =
            when (providerFragment) {
                ProviderFragment.OBJECT -> config[ResolverFromObjectFieldVariablesEnabled]
                ProviderFragment.QUERY -> config[ResolverFromQueryFieldVariablesEnabled]
            }
        if (
            !config[ResolverVariablesEnabled] ||
            !variablesEnabled ||
            (config[ResolverVariablesOnNonQueryFieldsOnly] && consumer.typeName == "Query") ||
            variableProviders
                .fromField(providerFragment)
                .map(VariableProviderPlan::owner)
                .distinct()
                .size >= config[ResolverFromFieldVariableOwnerLimit] ||
            (!directedParentCoverage && !chance(config[ResolverVariableWeight]))
        ) {
            return this
        }
        val variableCount =
            if (directedParentCoverage) 2 else Arb.int(config[ResolverVariableCount]).next(random)
        return (0 until variableCount).fold(this) { fragments, variableIndex ->
            val existingOwners =
                variableProviders.mapTo(linkedSetOf(), VariableProviderPlan::owner)
            val occurrences =
                fragments.argumentOccurrences()
                    .shuffled(random)
                    .filter { locatedOccurrence ->
                        locatedOccurrence.occurrence.existingVariableName == null &&
                            locatedOccurrence.occurrence.selectionPath.size in
                            config[ResolverFromFieldVariableUseDepth]
                    }
            val passiveUseOccurrences =
                occurrences.filter { locatedOccurrence ->
                    fragments
                        .fragment(locatedOccurrence.location)
                        .topLevelField(locatedOccurrence.occurrence) !in fieldSites
                }
            val ownerUseOccurrences =
                occurrences.filter { locatedOccurrence ->
                    fragments
                        .fragment(locatedOccurrence.location)
                        .topLevelField(locatedOccurrence.occurrence) in existingOwners
                }
            val providerArgumentOccurrences =
                occurrences.filter { locatedOccurrence ->
                    if (locatedOccurrence.location != providerLocation) {
                        return@filter false
                    }
                    val useBranch =
                        fragments.fragment(providerLocation).selections[
                            locatedOccurrence.occurrence.selectionPath.first()
                        ]
                    variableProviders
                        .fromField(providerFragment)
                        .any { provider ->
                            provider.owner == consumer && provider.selection == useBranch
                        }
                }
            var orderedOccurrences = occurrences
            if (
                passiveUseOccurrences.isNotEmpty() &&
                config[ResolverFromFieldPassiveUseWeight] > 0.0 &&
                chance(config[ResolverFromFieldPassiveUseWeight])
            ) {
                orderedOccurrences =
                    passiveUseOccurrences +
                        orderedOccurrences.filterNot(passiveUseOccurrences::contains)
            }
            if (
                ownerUseOccurrences.isNotEmpty() &&
                config[ResolverFromFieldVariableOwnerUseWeight] > 0.0 &&
                chance(config[ResolverFromFieldVariableOwnerUseWeight])
            ) {
                orderedOccurrences =
                    ownerUseOccurrences + orderedOccurrences.filterNot(ownerUseOccurrences::contains)
            }
            if (
                providerArgumentOccurrences.isNotEmpty() &&
                chance(config[ResolverFromFieldProviderArgumentVariableWeight])
            ) {
                orderedOccurrences =
                    providerArgumentOccurrences +
                        orderedOccurrences.filterNot(providerArgumentOccurrences::contains)
            }
            if (directedParentCoverage) {
                val preferredLocation =
                    if (variableIndex % 2 == 0) {
                        FragmentLocation.OBJECT
                    } else {
                        FragmentLocation.QUERY
                    }
                orderedOccurrences =
                    orderedOccurrences.sortedBy { occurrence ->
                        occurrence.location != preferredLocation
                    }
            }
            val candidate =
                orderedOccurrences.firstNotNullOfOrNull { locatedOccurrence ->
                    val fragment = fragments.fragment(locatedOccurrence.location)
                    val occurrence = locatedOccurrence.occurrence
                    val useField = fragment.topLevelField(occurrence)
                    val passiveUse = useField !in fieldSites
                    val useBranch = fragment.selections[occurrence.selectionPath.first()]
                    val useRank =
                        if (locatedOccurrence.location == providerLocation) {
                            structuralBranchRank(providerOwnerName, useBranch, ranks)
                        } else {
                            null
                        }
                    occurrence.target
                        .let { target ->
                            variableProviderPaths(
                                ownerName = providerOwnerName,
                                target = target,
                                consumerRank = ranks.getValue(consumer),
                                ranks = ranks,
                                maximumPathLength =
                                    config[ResolverFromFieldProviderPathLength].last,
                            )
                        }
                        .filter { provider ->
                            provider.pathLength() in
                                config[ResolverFromFieldProviderPathLength]
                        }.filter { provider ->
                            if (locatedOccurrence.location != providerLocation) {
                                true
                            } else {
                                val providerField = provider.topLevelField(providerOwnerName)
                                (!passiveUse || providerField !in fieldSites) &&
                                    providerField != useField &&
                                    structuralBranchRank(providerOwnerName, provider, ranks) <
                                    requireNotNull(useRank)
                            }
                        }.chooseProviderPath(
                            config[ResolverFromFieldProviderArgumentVariableWeight],
                        )?.let { provider ->
                            FromFieldCandidate(
                                location = locatedOccurrence.location,
                                occurrence = occurrence,
                                provider = provider,
                            )
                        }
                } ?: return@fold fragments
            val literalConvergence =
                fragments
                    .fragment(candidate.location)
                    .selectionAt(candidate.occurrence)
                    .subselections
                    .size >= 2 &&
                    chance(config[ResolverLiteralVariableConvergenceWeight])
            val variablePrefix =
                when (providerFragment) {
                    ProviderFragment.OBJECT -> "resolverVar"
                    ProviderFragment.QUERY -> "resolverQueryVar"
                }
            val variableName = "$variablePrefix${ranks.getValue(consumer)}_$variableIndex"
            val providerSelection = candidate.provider.withResponseAliases(variableName)
            val sharedOccurrence =
                fragments.argumentOccurrences()
                    .filter { locatedOccurrence ->
                        locatedOccurrence.occurrence.existingVariableName == null
                    }.filterNot { locatedOccurrence ->
                        locatedOccurrence.location == candidate.location &&
                            (
                                literalConvergence ||
                                    locatedOccurrence.occurrence.selectionPath ==
                                    candidate.occurrence.selectionPath
                            )
                    }.shuffled(random)
                    .firstOrNull { locatedOccurrence ->
                        val orderedAfterProvider =
                            if (locatedOccurrence.location != providerLocation) {
                                true
                            } else {
                                val useBranch =
                                    fragments.fragment(providerLocation).selections[
                                        locatedOccurrence.occurrence.selectionPath.first()
                                    ]
                                structuralBranchRank(providerOwnerName, providerSelection, ranks) <
                                    structuralBranchRank(providerOwnerName, useBranch, ranks)
                            }
                        orderedAfterProvider &&
                            locatedOccurrence.occurrence.selectionPath.size in
                            config[ResolverFromFieldVariableUseDepth] &&
                            locatedOccurrence.occurrence.target.let { target ->
                                providerSelection.isCompatibleProviderFor(providerOwnerName, target)
                            }
                    }
                    .takeUnless { directedParentCoverage }
            val replacedOccurrences =
                listOfNotNull(
                    LocatedArgumentOccurrence(candidate.location, candidate.occurrence),
                    sharedOccurrence,
                )
            val providerArgumentDependents =
                replacedOccurrences.flatMap { locatedOccurrence ->
                    val oldSelection =
                        fragments
                            .fragment(locatedOccurrence.location)
                            .selections[locatedOccurrence.occurrence.selectionPath.first()]
                    variableProviders.withIndex().mapNotNull { indexed ->
                        val matches =
                            when (val provider = indexed.value) {
                                is FromFieldVariableProviderPlan ->
                                    locatedOccurrence.location ==
                                        provider.providerFragment.location() &&
                                        provider.owner == consumer &&
                                        provider.selection == oldSelection
                                is FromArgumentVariableProviderPlan,
                                is FromProviderVariableProviderPlan,
                                -> false
                            }
                        indexed.takeIf { matches }?.let {
                            Triple(
                                indexed.index,
                                locatedOccurrence.location,
                                locatedOccurrence.occurrence.selectionPath.first(),
                            )
                        }
                    }
                }.distinctBy { dependent -> dependent.first }
            variableProviders +=
                FromFieldVariableProviderPlan(
                    owner = consumer,
                    variableName = variableName,
                    providerFragment = providerFragment,
                    selection = providerSelection,
                    nestedInput = candidate.occurrence.valuePath.isNotEmpty(),
                    listValue = candidate.occurrence.target is ListVariableTarget,
                    nullable = candidate.occurrence.target.nullable,
                    abstractPath = candidate.provider.hasAbstractPath(providerOwnerName),
                    useDepth = candidate.occurrence.selectionPath.size,
                    topLevelUseField =
                        fragments.fragment(candidate.location).topLevelField(candidate.occurrence),
                    literalConvergence = literalConvergence,
                )
            var updated =
                fragments
                    .appendSelection(providerLocation, providerSelection)
                    .replaceArgument(
                    location = candidate.location,
                    occurrence = candidate.occurrence,
                    variableName = variableName,
                    literalConvergence = literalConvergence,
                )
            if (sharedOccurrence != null) {
                updated =
                    updated.replaceArgument(
                        location = sharedOccurrence.location,
                        occurrence = sharedOccurrence.occurrence,
                        variableName = variableName,
                        literalConvergence = false,
                    )
            }
            providerArgumentDependents.forEach { (index, location, selectionIndex) ->
                val selection = updated.fragment(location).selections[selectionIndex]
                variableProviders[index] =
                    when (val provider = variableProviders[index]) {
                        is FromFieldVariableProviderPlan ->
                            provider.copy(selection = selection)
                        is FromArgumentVariableProviderPlan,
                        is FromProviderVariableProviderPlan,
                        -> provider
                    }
            }
            updated
        }
    }

    private fun FragmentSelectionPlan.isCompatibleProviderFor(
        ownerName: String,
        target: VariableTarget,
    ): Boolean {
        val selectionOwner = typeCondition ?: ownerName
        val field = schema.fieldsOn(selectionOwner).single { candidate ->
            candidate.name == fieldName
        }
        val child = subselections.singleOrNull()
            ?: return target.matches(
                field.type,
                config[ResolverVariableSingletonCoercionEnabled],
            ) && (!field.type.nullable || target.nullable)
        return !field.type.list &&
            (!field.type.nullable || target.acceptsNullableTraversal) &&
            child.isCompatibleProviderFor(field.type.namedType, target)
    }

    private fun FragmentPlan.topLevelField(occurrence: ArgumentOccurrence): FieldCoordinate {
        val selection = selections[occurrence.selectionPath.first()]
        return FieldCoordinate(
            typeName = selection.typeCondition ?: ownerName,
            fieldName = selection.fieldName,
        )
    }

    private fun FragmentPlan.selectionAt(
        occurrence: ArgumentOccurrence,
    ): FragmentSelectionPlan {
        var selections = selections
        lateinit var selected: FragmentSelectionPlan
        occurrence.selectionPath.forEach { index ->
            selected = selections[index]
            selections = selected.subselections
        }
        return selected
    }

    private fun FragmentPlan.argumentOccursAcrossNodeBoundary(
        occurrence: ArgumentOccurrence,
    ): Boolean {
        var currentOwner = ownerName
        var currentSelections = selections
        occurrence.selectionPath.forEach { index ->
            val selection = currentSelections[index]
            val selectionOwner = selection.typeCondition ?: currentOwner
            val selectedField =
                schema.fieldsOn(selectionOwner).single { field ->
                    field.name == selection.fieldName
                }
            if (
                schema.isComposite(selectedField.type.namedType) &&
                schema.possibleObjects(selectedField.type.namedType).let { possibleTypes ->
                    possibleTypes.isNotEmpty() &&
                        possibleTypes.all { possibleType -> possibleType.name in nodeSites }
                }
            ) {
                return true
            }
            currentOwner = selectedField.type.namedType
            currentSelections = selection.subselections
        }
        return false
    }

    private fun FragmentSelectionPlan.topLevelField(ownerName: String): FieldCoordinate =
        FieldCoordinate(
            typeName = typeCondition ?: ownerName,
            fieldName = fieldName,
        )

    /** Prefers argument-bearing providers when a profile requests provider-argument dependencies. */
    private fun List<FragmentSelectionPlan>.chooseProviderPath(
        providerArgumentVariableWeight: Double =
            config[ResolverFromFieldProviderArgumentVariableWeight],
    ): FragmentSelectionPlan? {
        if (isEmpty()) return null
        val argumentBearing = filter(FragmentSelectionPlan::hasPathArguments)
        val candidates =
            if (
                argumentBearing.isNotEmpty() &&
                chance(providerArgumentVariableWeight)
            ) {
                argumentBearing
            } else {
                this
            }
        val (nested, direct) =
            candidates.shuffled(random).partition { selection -> selection.pathLength() > 1 }
        return if (chance(config[ResolverNestedProviderPathWeight])) {
            nested.firstOrNull() ?: direct.firstOrNull()
        } else {
            direct.firstOrNull() ?: nested.firstOrNull()
        }
    }

    /**
     * Registered branches use the rank that makes ordinary generated resolver demand acyclic.
     * Passive branches have a stable schema order below every registered branch. Variable
     * production therefore always advances through one total order, including between two passive
     * branches generated for different resolver owners.
     */
    private fun structuralBranchRank(
        ownerName: String,
        selection: FragmentSelectionPlan,
        ranks: Map<FieldCoordinate, Int>,
    ): Int {
        val fields: List<FieldDefinitionSpec> = schema.fieldsOn(ownerName)
        val fieldIndex: Int =
            fields.indexOfFirst { candidate -> candidate.name == selection.fieldName }
        check(fieldIndex >= 0) {
            "Generated branch ${selection.fieldName} does not belong to $ownerName"
        }
        val field: FieldDefinitionSpec = fields[fieldIndex]
        return ranks[field.coordinate] ?: fieldIndex - fields.size
    }

    private fun FragmentPlan.argumentOccurrences(): List<ArgumentOccurrence> =
        argumentOccurrences(
            ownerName = ownerName,
            selections = selections,
            selectionPath = emptyList(),
            beneathParent = false,
        )

    private fun argumentOccurrences(
        ownerName: String,
        selections: List<FragmentSelectionPlan>,
        selectionPath: List<Int>,
        beneathParent: Boolean,
    ): List<ArgumentOccurrence> =
        selections.flatMapIndexed { index, selection ->
            val selectionOwner = selection.typeCondition ?: ownerName
            val field =
                schema
                    .fieldsOn(selectionOwner)
                    .singleOrNull { it.name == selection.fieldName }
                    ?: return@flatMapIndexed emptyList()
            val path = selectionPath + index
            (
                if (beneathParent) {
                    emptyList()
                } else {
                    field.arguments.flatMap { argument ->
                        selection.arguments
                            .getValue(argument.name)
                            .variableOccurrences()
                            .map { valueOccurrence ->
                                ArgumentOccurrence(
                                    selectionPath = path,
                                    argument = argument,
                                    valuePath = valueOccurrence.path,
                                    target = valueOccurrence.target,
                                    existingVariableName = valueOccurrence.existingVariableName,
                                )
                            }
                    }
                }
            ) +
                (
                    field.type.namedType
                        .takeIf(schema::isComposite)
                        ?.let { nestedOwner ->
                            argumentOccurrences(
                                ownerName = nestedOwner,
                                selections = selection.subselections,
                                selectionPath = path,
                                beneathParent = beneathParent || field.isParentField,
                            )
                        }.orEmpty()
                )
        }

    private fun variableProviderPaths(
        ownerName: String,
        target: VariableTarget,
        consumerRank: Int,
        ranks: Map<FieldCoordinate, Int>,
        visitedTypes: Set<String> = emptySet(),
        maximumPathLength: Int =
            config[ResolverFromFieldProviderPathLength].last,
    ): List<FragmentSelectionPlan> =
        if (ownerName in visitedTypes || maximumPathLength <= 0) {
            emptyList()
        } else {
        schema
            .fieldsOn(ownerName)
            .filter { field ->
                !field.isGeneratedHashField() &&
                    field.hasOnlyLowerRankedResolverDependencies(consumerRank, ranks)
            }.flatMap { field ->
                when {
                    target.matches(
                        field.type,
                        config[ResolverVariableSingletonCoercionEnabled],
                    ) &&
                        (!field.type.nullable || target.nullable) ->
                        listOf(
                            FragmentSelectionPlan(
                                fieldName = field.name,
                                arguments = providerArguments(field),
                                subselections = emptyList(),
                            ),
                        )

                    !field.type.list &&
                        maximumPathLength > 1 &&
                        schema.isComposite(field.type.namedType) &&
                        (!field.type.nullable || target.acceptsNullableTraversal) ->
                        variableProviderPaths(
                            ownerName = field.type.namedType,
                            target = target,
                            consumerRank = consumerRank,
                            ranks = ranks,
                            visitedTypes = visitedTypes + ownerName,
                            maximumPathLength = maximumPathLength - 1,
                        ).map { nested ->
                            FragmentSelectionPlan(
                                fieldName = field.name,
                                arguments = providerArguments(field),
                                subselections = listOf(nested),
                            )
                        }

                    else -> emptyList()
                }
            }
        }

    private fun providerArguments(
        field: FieldDefinitionSpec,
    ): Map<String, InputValuePlan> =
        field.arguments.associate { argument ->
            argument.name to inputLiteral(argument.type)
        }

    private fun FragmentSelectionPlan.hasAbstractPath(ownerName: String): Boolean {
        val selectionOwner = typeCondition ?: ownerName
        if (fieldName == "__typename") {
            return typeCondition != null ||
                (
                    schema.isComposite(ownerName) &&
                        schema.possibleObjects(ownerName).size > 1
                )
        }
        val field = schema.fieldsOn(selectionOwner).single { it.name == fieldName }
        if (schema.isComposite(field.type.namedType)) {
            if (typeCondition != null || schema.possibleObjects(field.type.namedType).size > 1) {
                return true
            }
            return subselections.any { it.hasAbstractPath(field.type.namedType) }
        }
        return false
    }

    private fun inputLiteral(
        type: InputTypeSpec,
        objectPath: Set<String> = emptySet(),
    ): InputValuePlan {
        if (type is ListInputTypeSpec && type.element.reachesAny(objectPath)) {
            return ListInputPlan(type, emptyList())
        }
        if (
            type is InputObjectInputTypeSpec &&
            type.nullable &&
            type.reachesAny(objectPath)
        ) {
            return NullInputPlan(type)
        }
        if (type.nullable && chance(config[NullValueWeight])) {
            return NullInputPlan(type)
        }
        return when (type) {
            is ScalarInputTypeSpec -> scalarInputLiteral(type)
            is ListInputTypeSpec ->
                ListInputPlan(
                    type = type,
                    elements =
                        List(Arb.int(config[ListValueSize]).next(random)) {
                            inputLiteral(type.element, objectPath)
                        },
                )
            is InputObjectInputTypeSpec -> {
                if (type.name in objectPath) {
                    require(type.nullable) {
                        "Recursive input-object edge ${type.name} must be nullable"
                    }
                    NullInputPlan(type)
                } else {
                    val definition =
                        schema.inputObjects.single { candidate -> candidate.name == type.name }
                    ObjectInputPlan(
                        type = type,
                        fields =
                            definition.fields.associate { field ->
                                field.name to inputLiteral(field.type, objectPath + type.name)
                            },
                    )
                }
            }
        }
    }

    private fun InputTypeSpec.reachesAny(
        targets: Set<String>,
        visited: Set<String> = emptySet(),
    ): Boolean =
        when (this) {
            is ScalarInputTypeSpec -> false
            is ListInputTypeSpec -> element.reachesAny(targets, visited)
            is InputObjectInputTypeSpec -> {
                name in targets ||
                    (
                        name !in visited &&
                            schema.inputObjects
                                .single { it.name == name }
                                .fields
                                .any { field -> field.type.reachesAny(targets, visited + name) }
                    )
            }
        }

    private fun scalarInputLiteral(type: ScalarInputTypeSpec): InputLiteralPlan {
        val salt = Arb.int(config[InputScalarValueRange]).next(random)
        val value: Any =
            when (type.scalar) {
                ScalarKind.BOOLEAN -> salt % 2 == 0
                ScalarKind.FLOAT -> salt.toDouble() + 0.5
                ScalarKind.ID -> "id-$salt"
                ScalarKind.INT -> salt
                ScalarKind.STRING -> "value-$salt"
            }
        return InputLiteralPlan(type, value)
    }

    private fun plan(
        type: OutputTypeSpec,
        path: String,
        allowNullOrError: Boolean = true,
        objectPath: Set<String> = emptySet(),
    ): ValuePlan {
        if (type.namedType == GENERATED_HASH_TYPE) {
            return GeneratedHashPlan(path.hashCode())
        }
        val closesRecursivePath =
            schema.isComposite(type.namedType) &&
                schema.possibleObjects(type.namedType).any { it.name in objectPath }
        if (closesRecursivePath && type.list) return ListPlan(emptyList())
        if (closesRecursivePath) {
            return if (type.nullable) NullPlan else ErrorPlan
        }
        if (allowNullOrError && chance(config[ErrorValueWeight])) return ErrorPlan
        if (
            allowNullOrError &&
            type.nullable &&
            chance(config[NullValueWeight])
        ) {
            return NullPlan
        }
        if (type.list) {
            val size = Arb.int(config[ListValueSize]).next(random)
            val elementType = type.elementType()
            return ListPlan(
                (0 until size).map { index ->
                    plan(elementType, "$path[$index]", objectPath = objectPath)
                },
            )
        }
        return ScalarKind.entries
            .singleOrNull { it.graphQLName == type.namedType }
            ?.let { scalarPlan(it, path) }
            ?: schema.enumNamed(type.namedType)?.let { enumType ->
                EnumPlan(Arb.element(enumType.values).next(random))
            }
            ?: Arb.element(schema.possibleObjects(type.namedType))
                .next(random)
                .name
                .let { typeName ->
                    if (typeName in objectPath) {
                        require(type.nullable) {
                            "Recursive output edge to $typeName must be nullable or a list"
                        }
                        NullPlan
                    } else {
                        objectPlan(
                            typeName = typeName,
                            path = path,
                            objectPath = objectPath,
                        )
                    }
                }
    }

    private fun objectPlan(
        typeName: String,
        path: String,
        nodeResolverRoot: Boolean = false,
        objectPath: Set<String> = emptySet(),
    ): ObjectPlan {
        val type = schema.objectNamed(typeName)
        val isNodeBoundary = typeName in nodeSites && !nodeResolverRoot
        val fields =
            buildMap {
                if (type.implementsNode) {
                    put(
                        FieldCoordinate(typeName, "id"),
                        if (nodeResolverRoot) {
                            InputIdPlan
                        } else {
                            ScalarPlan(ScalarKind.ID, "$path-id")
                        },
                    )
                }
                if (!isNodeBoundary) {
                    type.fields
                        .filter { it.arguments.isEmpty() }
                        .filter { it.coordinate !in fieldSites }
                        .forEach { field ->
                            put(
                                field.coordinate,
                                plan(
                                    field.type,
                                    "$path.${field.name}",
                                    objectPath = objectPath + typeName,
                                ),
                            )
                        }
                }
            }
        return ObjectPlan(typeName, fields)
    }

    private fun scalarPlan(
        scalar: ScalarKind,
        path: String,
    ): ScalarPlan {
        val salt = Arb.int(0..10_000).next(random)
        val value: Any =
            when (scalar) {
                ScalarKind.BOOLEAN -> salt % 2 == 0
                ScalarKind.FLOAT -> salt.toDouble() / 10.0
                ScalarKind.ID -> "$path-$salt"
                ScalarKind.INT -> salt
                ScalarKind.STRING -> "$path-$salt"
            }
        return ScalarPlan(scalar, value)
    }

    private fun ValuePlan.parentFieldCount(): Int =
        when (this) {
            is ListPlan -> elements.sumOf { element -> element.parentFieldCount() }
            is ObjectPlan ->
                fields.entries.sumOf { (coordinate, value) ->
                    val outputField =
                        schema.fieldsOn(typeName).singleOrNull { candidate ->
                            candidate.name == coordinate.fieldName
                        }
                    value.parentFieldCount() + if (outputField?.isParentField == true) 1 else 0
                }
            else -> 0
        }

    private fun ValuePlan.countRootFieldReferences(): Int =
        when (this) {
            is RootFieldReferencePlan -> 1
            is ListPlan -> elements.sumOf { value -> value.countRootFieldReferences() }
            is ObjectPlan -> fields.values.sumOf { value -> value.countRootFieldReferences() }
            else -> 0
        }

    private fun field(coordinate: FieldCoordinate): FieldDefinitionSpec =
        fieldOrNull(coordinate)
            ?: error(
                "Unknown generated field $coordinate; fields on ${coordinate.typeName}: " +
                    schema.fieldsOn(coordinate.typeName).map(FieldDefinitionSpec::name),
            )

    private fun fieldOrNull(coordinate: FieldCoordinate): FieldDefinitionSpec? =
        schema
            .fieldsOn(coordinate.typeName)
            .singleOrNull { it.name == coordinate.fieldName }

    private fun FieldDefinitionSpec.hasOnlyLowerRankedResolverDependencies(
        consumerRank: Int,
        ranks: Map<FieldCoordinate, Int>,
    ): Boolean =
        possibleSourceCoordinates().all { coordinate ->
            coordinate !in fieldSites || ranks.getValue(coordinate) < consumerRank
        }

    private fun FieldDefinitionSpec.possibleSourceCoordinates(): Set<FieldCoordinate> =
        if (schema.allObjects.any { objectType -> objectType.name == ownerName }) {
            setOf(coordinate)
        } else {
            schema
                .possibleObjects(ownerName)
                .mapTo(linkedSetOf()) { possibleType ->
                    FieldCoordinate(possibleType.name, name)
                }
        }

    private fun FieldDefinitionSpec.isGeneratedPassiveAbstractOutput(): Boolean =
        config[PassiveAbstractOutputTypeWeight] > 0.0 &&
            ownerName != "Query" &&
            schema.isComposite(type.namedType) &&
            schema.allObjects.none { objectType -> objectType.name == type.namedType }

    private fun FieldDefinitionSpec.isGeneratedParentSpineResolver(): Boolean =
        config[ParentFieldsEnabled] &&
            (
                ownerName in
                    setOf(
                        GENERATED_PARENT_ROOT_TYPE,
                        GENERATED_PARENT_CHILD_TYPE,
                        GENERATED_PARENT_GRANDCHILD_TYPE,
                        GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
                    ) ||
                    (
                        config[RandomParentFieldsEnabled] &&
                            ownerName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX) &&
                            (
                                name == "value0" ||
                                    (
                                        config[SometimesPassiveFieldWeight] > 0.0 &&
                                            name == GENERATED_SOMETIMES_PASSIVE_PARENT_FIELD
                                    )
                            )
                    )
            )

    // Names the argumentless constant resolver whose unused parent input exercises speculative
    // demand when an ancestor resolver supplies the active field's value.
    private fun FieldCoordinate.isGeneratedSometimesPassiveParentResolver(): Boolean =
        config[RandomParentFieldsEnabled] &&
            config[SometimesPassiveFieldWeight] > 0.0 &&
            typeName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX) &&
            fieldName == GENERATED_SOMETIMES_PASSIVE_PARENT_FIELD

    private fun FieldCoordinate.isGeneratedRandomParentDiagonalResolver(): Boolean =
        config[RandomParentFieldsEnabled] &&
            typeName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX) &&
            fieldName == "value0"

    private fun FieldCoordinate.isGeneratedParentResult(): Boolean =
        config[ParentFieldsEnabled] &&
            typeName == GENERATED_PARENT_GREAT_GRANDCHILD_TYPE &&
            fieldName == GENERATED_PARENT_RESULT_FIELD

    private fun generatedGreatGrandparentFragment(): FragmentPlan =
        FragmentPlan(
            ownerName = GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
            selections =
                listOf(
                    FragmentSelectionPlan(
                        fieldName = GENERATED_PARENT_FIELD,
                        arguments = emptyMap(),
                        subselections =
                            listOf(
                                FragmentSelectionPlan(
                                    fieldName = GENERATED_PARENT_FIELD,
                                    arguments = emptyMap(),
                                    subselections =
                                        listOf(
                                            FragmentSelectionPlan(
                                                fieldName = GENERATED_PARENT_FIELD,
                                                arguments = emptyMap(),
                                                subselections =
                                                    listOf(
                                                        FragmentSelectionPlan(
                                                            fieldName =
                                                                GENERATED_PARENT_VALUE_FIELD,
                                                            arguments = emptyMap(),
                                                            subselections = emptyList(),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    private fun FragmentPlan.maximumParentSelectionDepth(): Int =
        selections.maxOfOrNull { selection ->
            selection.maximumParentSelectionDepth(ownerName, parentDepth = 0)
        } ?: 0

    private fun FragmentSelectionPlan.maximumParentSelectionDepth(
        ownerName: String,
        parentDepth: Int,
    ): Int {
        val selectionOwner = typeCondition ?: ownerName
        val field =
            schema.fieldsOn(selectionOwner).singleOrNull { candidate ->
                candidate.name == fieldName
            } ?: return parentDepth
        val nextParentDepth = if (field.isParentField) parentDepth + 1 else 0
        val nestedMaximum =
            subselections.maxOfOrNull { selection ->
                selection.maximumParentSelectionDepth(
                    ownerName = field.type.namedType,
                    parentDepth = nextParentDepth,
                )
            } ?: 0
        return maxOf(nextParentDepth, nestedMaximum)
    }

    private fun chance(weight: Double): Boolean =
        Arb.double(0.0, 1.0).next(random) < weight

    private fun <T> List<T>.shuffled(random: RandomSource): List<T> {
        val remaining = toMutableList()
        val result = mutableListOf<T>()
        while (remaining.isNotEmpty()) {
            result += remaining.removeAt(Arb.int(0 until remaining.size).next(random))
        }
        return result
    }
}

private data class ResolverFragmentPlans(
    val objectFragment: FragmentPlan,
    val queryFragment: FragmentPlan,
)

private enum class FragmentLocation {
    OBJECT,
    QUERY,
}

private fun ProviderFragment.location(): FragmentLocation =
    when (this) {
        ProviderFragment.OBJECT -> FragmentLocation.OBJECT
        ProviderFragment.QUERY -> FragmentLocation.QUERY
    }

private data class LocatedArgumentOccurrence(
    val location: FragmentLocation,
    val occurrence: ArgumentOccurrence,
)

private data class FromFieldCandidate(
    val location: FragmentLocation,
    val occurrence: ArgumentOccurrence,
    val provider: FragmentSelectionPlan,
)

private data class FromArgumentCandidate(
    val location: FragmentLocation,
    val occurrence: ArgumentOccurrence,
    val source: FromArgumentSource,
)

internal data class FragmentPlan(
    val ownerName: String,
    val selections: List<FragmentSelectionPlan>,
) {
    fun materialize(
        schema: ViaductSchema,
        variableField: ViaductSchema.ObjectField,
    ): Fragment =
        if (selections.isEmpty()) {
            Fragment.of(
                nominalType = schema.requireType(ownerName) as ViaductSchema.Object,
                subselections = selectionForestOf(),
            )
        } else {
            val parsed = schema.fragmentFrom(source(), variableField = variableField)
            Fragment.of(
                nominalType = parsed.nominalType,
                materializeSelections =
                    selections.materialize(schema, parsed.materializeSelections),
            )
        }

    fun errorArgumentCount(): Int =
        selections.sumOf(FragmentSelectionPlan::errorArgumentCount)

    fun selectionCount(): Int =
        selections.sumOf(FragmentSelectionPlan::selectionCount)

    fun selectionDepth(): Int =
        selections.maxOfOrNull(FragmentSelectionPlan::selectionDepth) ?: 0

    fun source(): String =
        if (selections.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine("fragment Generated on $ownerName {")
                selections.forEach { append(it.source("  ")) }
                append("}")
            }
        }
}

internal data class FragmentSelectionPlan(
    val fieldName: String,
    val arguments: Map<String, InputValuePlan>,
    val subselections: List<FragmentSelectionPlan>,
    val typeCondition: String? = null,
    val alias: String? = null,
) {
    fun source(indent: String): String =
        buildString {
            if (typeCondition != null) {
                appendLine("$indent... on $typeCondition {")
            }
            val fieldIndent = if (typeCondition == null) indent else "$indent  "
            append(fieldIndent)
            if (alias != null) append("$alias: ")
            append(fieldName)
            if (arguments.isNotEmpty()) {
                append(
                    arguments.entries.joinToString(prefix = "(", postfix = ")") { (name, value) ->
                        "$name: ${value.source()}"
                    },
                )
            }
            if (subselections.isEmpty()) {
                appendLine()
            } else {
                appendLine(" {")
                subselections.forEach { append(it.source("$fieldIndent  ")) }
                appendLine("$fieldIndent}")
            }
            if (typeCondition != null) {
                appendLine("$indent}")
            }
        }

    fun materialize(
        schema: ViaductSchema,
        owner: ViaductSchema.Object,
        variableField: ViaductSchema.ObjectField,
    ): Selection =
        FragmentPlan(owner.name, listOf(this))
            .materialize(schema, variableField)
            .subselections
            .single()

    fun errorArgumentCount(): Int =
        arguments.values.count { value -> value is ErrorInputPlan } +
            subselections.sumOf(FragmentSelectionPlan::errorArgumentCount)

    fun selectionCount(): Int =
        1 + subselections.sumOf(FragmentSelectionPlan::selectionCount)

    fun selectionDepth(): Int =
        1 + (subselections.maxOfOrNull(FragmentSelectionPlan::selectionDepth) ?: 0)
}

private fun List<FragmentSelectionPlan>.materialize(
    schema: ViaductSchema,
    parsedSelections: MaterializeSelectionForest,
): MaterializeSelectionForest {
    val parsed =
        buildList {
            parsedSelections.forEach(::add)
        }
    require(size == parsed.size) {
        "Parsed resolver fragment did not preserve planned selection occurrences"
    }
    return zip(parsed)
        .map { (plan, selection) ->
            selection.withErrorArguments(
                plan.arguments
                    .filterValues { argument -> argument is ErrorInputPlan }
                    .keys,
            ).let { materializedSelection ->
                MaterializeSelection.of(
                    responseKey = materializedSelection.responseKey,
                    key = materializedSelection.key,
                    possibleTypes = selection.possibleTypes,
                    inclusionCondition = materializedSelection.inclusionCondition,
                    subselections =
                        plan.subselections.materialize(schema, selection.subselections),
                )
            }
        }.toMaterializeSelectionForest()
}

internal sealed interface InputValuePlan {
    fun source(): String

    fun variableTarget(): VariableTarget?
}

internal data class InputLiteralPlan(
    val type: ScalarInputTypeSpec,
    val value: Any,
) : InputValuePlan {
    override fun source(): String =
        when (type.scalar) {
            ScalarKind.BOOLEAN, ScalarKind.FLOAT, ScalarKind.INT -> value.toString()
            ScalarKind.ID, ScalarKind.STRING ->
                "\"" + (value as String).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

    override fun variableTarget(): VariableTarget =
        ScalarVariableTarget(type.scalar, type.nullable)
}

internal data class ListInputPlan(
    val type: ListInputTypeSpec,
    val elements: List<InputValuePlan>,
) : InputValuePlan {
    override fun source(): String =
        elements.joinToString(prefix = "[", postfix = "]") { it.source() }

    override fun variableTarget(): VariableTarget? {
        if (elements.any(InputValuePlan::containsVariable)) return null
        val shape = type.scalarListShape() ?: return null
        return ListVariableTarget(
            scalar = shape.scalar,
            nullable = type.nullable,
            elementNullabilities = shape.elementNullabilities,
        )
    }
}

internal data class ObjectInputPlan(
    val type: InputObjectInputTypeSpec,
    val fields: Map<String, InputValuePlan>,
) : InputValuePlan {
    override fun source(): String =
        fields.entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
            "$name: ${value.source()}"
        }

    override fun variableTarget(): VariableTarget? = null
}

internal data class NullInputPlan(
    val type: InputTypeSpec,
) : InputValuePlan {
    override fun source(): String = "null"

    override fun variableTarget(): VariableTarget? = null
}

internal data class VariableInputPlan(
    val variableName: String,
    val target: VariableTarget? = null,
) : InputValuePlan {
    override fun source(): String = "\$$variableName"

    override fun variableTarget(): VariableTarget? = target
}

internal data class ErrorInputPlan(
    val placeholder: InputValuePlan,
) : InputValuePlan {
    override fun source(): String = placeholder.source()

    override fun variableTarget(): VariableTarget? = null
}

internal sealed interface VariableTarget {
    val nullable: Boolean

    val acceptsNullableTraversal: Boolean

    fun matches(
        type: OutputTypeSpec,
        allowSingletonCoercion: Boolean = false,
    ): Boolean

    fun accepts(
        type: InputTypeSpec,
        allowSingletonCoercion: Boolean = false,
    ): Boolean
}

internal data class ScalarVariableTarget(
    val scalar: ScalarKind,
    override val nullable: Boolean,
) : VariableTarget {
    override val acceptsNullableTraversal: Boolean
        get() = nullable

    override fun matches(
        type: OutputTypeSpec,
        allowSingletonCoercion: Boolean,
    ): Boolean =
        !type.list && type.namedType == scalar.graphQLName

    override fun accepts(
        type: InputTypeSpec,
        allowSingletonCoercion: Boolean,
    ): Boolean =
        type is ScalarInputTypeSpec &&
            type.scalar == scalar &&
            (!type.nullable || nullable)
}

internal data class ListVariableTarget(
    val scalar: ScalarKind,
    override val nullable: Boolean,
    val elementNullabilities: List<Boolean>,
) : VariableTarget {
    constructor(
        scalar: ScalarKind,
        nullable: Boolean,
        elementNullable: Boolean,
    ) : this(scalar, nullable, listOf(elementNullable))

    init {
        require(elementNullabilities.isNotEmpty())
    }

    override val acceptsNullableTraversal: Boolean
        get() = nullable

    override fun matches(
        type: OutputTypeSpec,
        allowSingletonCoercion: Boolean,
    ): Boolean =
        type.namedType == scalar.graphQLName &&
            type.listDepth.let { sourceDepth ->
                sourceDepth == elementNullabilities.size ||
                    (allowSingletonCoercion && sourceDepth < elementNullabilities.size)
            } &&
            type.nullabilities().zip(targetNullabilities()).all { (source, target) ->
                !source || target
            }

    override fun accepts(
        type: InputTypeSpec,
        allowSingletonCoercion: Boolean,
    ): Boolean {
        val shape = type.scalarShape() ?: return false
        return shape.scalar == scalar &&
            (
                shape.listDepth == elementNullabilities.size ||
                    (allowSingletonCoercion && shape.listDepth < elementNullabilities.size)
            ) &&
            shape.nullabilities.zip(targetNullabilities()).all { (source, target) ->
                !source || target
            }
    }

    private fun targetNullabilities(): List<Boolean> = listOf(nullable) + elementNullabilities
}

private data class ScalarListShape(
    val scalar: ScalarKind,
    val nullabilities: List<Boolean>,
) {
    val listDepth: Int
        get() = nullabilities.size - 1

    val elementNullabilities: List<Boolean>
        get() = nullabilities.drop(1)
}

private fun InputTypeSpec.scalarShape(): ScalarListShape? =
    when (this) {
        is ScalarInputTypeSpec -> ScalarListShape(scalar, listOf(nullable))
        is ListInputTypeSpec ->
            element.scalarShape()?.let { elementShape ->
                ScalarListShape(
                    scalar = elementShape.scalar,
                    nullabilities = listOf(nullable) + elementShape.nullabilities,
                )
            }
        is InputObjectInputTypeSpec -> null
    }

private fun ListInputTypeSpec.scalarListShape(): ScalarListShape? = scalarShape()

private fun OutputTypeSpec.nullabilities(): List<Boolean> =
    if (list) {
        listOf(nullable, elementNullable) + nestedElementNullabilities
    } else {
        listOf(nullable)
    }

private data class FromArgumentSource(
    val argument: ArgumentDefinitionSpec,
    val inputPath: List<String>,
    val type: InputTypeSpec,
    val nullableTraversal: Boolean,
)

internal sealed interface VariableProviderPlan {
    val owner: FieldCoordinate
    val variableName: String
    val nestedInput: Boolean
    val listValue: Boolean
    val nullable: Boolean
    val literalConvergence: Boolean
}

private fun Iterable<VariableProviderPlan>.fromField(
    providerFragment: ProviderFragment,
): List<FromFieldVariableProviderPlan> =
    filterIsInstance<FromFieldVariableProviderPlan>()
        .filter { provider -> provider.providerFragment == providerFragment }

internal data class FromArgumentVariableProviderPlan(
    override val owner: FieldCoordinate,
    override val variableName: String,
    val argumentName: String,
    val inputPath: List<String> = emptyList(),
    val nullableTraversal: Boolean = false,
    override val nestedInput: Boolean,
    override val listValue: Boolean,
    override val nullable: Boolean,
    override val literalConvergence: Boolean,
) : VariableProviderPlan {
    val argumentPath: List<String>
        get() = listOf(argumentName) + inputPath
}

internal data class FromProviderVariableProviderPlan(
    override val owner: FieldCoordinate,
    override val variableName: String,
    val target: VariableTarget,
    override val nestedInput: Boolean,
    val argumentSensitive: Boolean,
) : VariableProviderPlan {
    override val listValue: Boolean = target is ListVariableTarget
    override val nullable: Boolean = target.nullable
    override val literalConvergence: Boolean = false

    fun value(
        arguments: Arguments.Resolved,
        field: ViaductSchema.Field,
    ): EngineInputData {
        val hash =
            stableGeneratedHash(
                owner.toString(),
                variableName,
                arguments.resolutionFingerprint(field).value,
            )
        return target.generatedProviderValue(hash)
    }
}

internal data class FromFieldVariableProviderPlan(
    override val owner: FieldCoordinate,
    override val variableName: String,
    val providerFragment: ProviderFragment,
    val selection: FragmentSelectionPlan,
    override val nestedInput: Boolean,
    override val listValue: Boolean,
    override val nullable: Boolean,
    val abstractPath: Boolean,
    val useDepth: Int,
    val topLevelUseField: FieldCoordinate,
    override val literalConvergence: Boolean,
) : VariableProviderPlan {
    fun source(): String =
        FragmentPlan(providerOwnerName(), listOf(selection)).source()

    fun providerOwnerName(): String =
        when (providerFragment) {
            ProviderFragment.OBJECT -> owner.typeName
            ProviderFragment.QUERY -> "Query"
        }

    fun responsePath(): List<String> =
        buildList {
            var current = selection
            while (true) {
                add(current.alias ?: current.fieldName)
                if (current.subselections.isEmpty()) break
                current = current.subselections.single()
            }
    }
}

private data class GeneratedFromFieldFeatures(
    val variableCount: Int,
    val literalVariableConvergenceCount: Int,
    val passiveTopLevelVariableUseCount: Int,
    val providerArgumentVariableCount: Int,
    val maximumPathLength: Int,
    val maximumVariableUseDepth: Int,
    val hasAbstractProviderPath: Boolean,
)

private fun List<FromFieldVariableProviderPlan>.features(
    fieldSites: Set<FieldCoordinate>,
): GeneratedFromFieldFeatures =
    GeneratedFromFieldFeatures(
        variableCount = size,
        literalVariableConvergenceCount = count(VariableProviderPlan::literalConvergence),
        passiveTopLevelVariableUseCount =
            count { provider -> provider.topLevelUseField !in fieldSites },
        providerArgumentVariableCount =
            count { provider -> provider.providerArgumentVariableNames().isNotEmpty() },
        maximumPathLength = maxOfOrNull { provider -> provider.responsePath().size } ?: 0,
        maximumVariableUseDepth = maxOfOrNull(FromFieldVariableProviderPlan::useDepth) ?: 0,
        hasAbstractProviderPath = any(FromFieldVariableProviderPlan::abstractPath),
    )

private fun VariableTarget.generatedProviderValue(hash: Int): EngineInputData =
    when (this) {
        is ScalarVariableTarget -> scalar.generatedProviderScalar(hash)
        is ListVariableTarget ->
            elementNullabilities.foldRight<Boolean, EngineInputData>(
                scalar.generatedProviderScalar(hash),
            ) { _, value -> listOf(value) }
    }

private fun ScalarKind.generatedProviderScalar(hash: Int): EngineInputData =
    when (this) {
        ScalarKind.BOOLEAN -> hash and 1 == 0
        ScalarKind.FLOAT -> hash.toDouble()
        ScalarKind.ID -> "provider-$hash"
        ScalarKind.INT -> hash
        ScalarKind.STRING -> "provider-$hash"
    }

private enum class ProviderIntermediateOutcome {
    NONE,
    NULL,
    ERROR,
}

private fun FromFieldVariableProviderPlan.intermediateOutcome(
    fieldValues: Map<FieldCoordinate, ValuePlan>,
): ProviderIntermediateOutcome {
    var ownerName = providerOwnerName()
    var containingObjectPlan: ObjectPlan? = null
    var current = selection
    while (current.subselections.isNotEmpty()) {
        val coordinate =
            FieldCoordinate(
                typeName = current.typeCondition ?: ownerName,
                fieldName = current.fieldName,
            )
        val valuePlan =
            fieldValues[coordinate]
                ?: containingObjectPlan?.fields?.get(coordinate)
                ?: return ProviderIntermediateOutcome.NONE
        when (valuePlan) {
            NullPlan -> return ProviderIntermediateOutcome.NULL
            ErrorPlan -> return ProviderIntermediateOutcome.ERROR
            is ObjectPlan -> {
                containingObjectPlan = valuePlan
                ownerName = valuePlan.typeName
            }
            else -> return ProviderIntermediateOutcome.NONE
        }
        current = current.subselections.singleOrNull()
            ?: return ProviderIntermediateOutcome.NONE
    }
    return ProviderIntermediateOutcome.NONE
}

private fun FragmentSelectionPlan.withResponseAliases(
    variableName: String,
    depth: Int = 0,
): FragmentSelectionPlan =
    copy(
        alias = "${variableName}Source$depth",
        subselections =
            subselections.map { selection ->
                selection.withResponseAliases(variableName, depth + 1)
            },
    )

private fun FragmentSelectionPlan.pathLength(): Int =
    1 + (subselections.singleOrNull()?.pathLength() ?: 0)

private fun FragmentSelectionPlan.hasPathArguments(): Boolean =
    arguments.isNotEmpty() ||
        subselections.singleOrNull()?.hasPathArguments() == true

private fun sensitiveScalar(
    scalar: ScalarKind,
    input: EngineObjectData.Sync,
    arguments: Arguments.Resolved,
    argumentField: ViaductSchema.Field,
    applicationOrdinal: Int? = null,
): EngineOutputData {
    val fingerprint =
        input.resolutionFingerprint().value +
            "|" +
            arguments.resolutionFingerprint(argumentField).value +
            applicationOrdinal?.let { "|ordinal:$it" }.orEmpty()
    val hash = fingerprint.hashCode()
    return when (scalar) {
        ScalarKind.BOOLEAN -> hash and 1 == 0
        ScalarKind.FLOAT -> hash.toDouble()
        ScalarKind.ID -> "generated-$hash"
        ScalarKind.INT -> hash
        ScalarKind.STRING -> "generated-$hash"
    }
}

private data class ArgumentOccurrence(
    val selectionPath: List<Int>,
    val argument: ArgumentDefinitionSpec,
    val valuePath: List<InputValueStep>,
    val target: VariableTarget,
    val existingVariableName: String?,
)

private fun List<FragmentSelectionPlan>.replaceArgument(
    selectionPath: List<Int>,
    argumentName: String,
    valuePath: List<InputValueStep>,
    value: InputValuePlan,
): List<FragmentSelectionPlan> {
    val selectedIndex = selectionPath.first()
    return mapIndexed { index, selection ->
        when {
            index != selectedIndex -> selection
            selectionPath.size == 1 ->
                selection.copy(
                    arguments =
                        selection.arguments +
                            (
                                argumentName to
                                    selection.arguments
                                        .getValue(argumentName)
                                        .replace(valuePath, value)
                            ),
                )
            else ->
                selection.copy(
                    subselections =
                        selection.subselections.replaceArgument(
                            selectionPath = selectionPath.drop(1),
                            argumentName = argumentName,
                            valuePath = valuePath,
                            value = value,
                        ),
                )
        }
    }
}

private fun List<FragmentSelectionPlan>.replaceArgumentWithLiteralConvergence(
    selectionPath: List<Int>,
    argumentName: String,
    valuePath: List<InputValueStep>,
    variableName: String,
    target: VariableTarget,
): List<FragmentSelectionPlan> {
    val selectedIndex = selectionPath.first()
    return flatMapIndexed { index, selection ->
        when {
            index != selectedIndex -> listOf(selection)
            selectionPath.size > 1 ->
                listOf(
                    selection.copy(
                        subselections =
                            selection.subselections.replaceArgumentWithLiteralConvergence(
                                selectionPath = selectionPath.drop(1),
                                argumentName = argumentName,
                                valuePath = valuePath,
                                variableName = variableName,
                                target = target,
                            ),
                    ),
                )
            else -> {
                require(selection.subselections.size >= 2)
                val symbolicArguments =
                    selection.arguments +
                        (
                            argumentName to
                                selection.arguments
                                    .getValue(argumentName)
                                    .replace(valuePath, VariableInputPlan(variableName, target))
                        )
                listOf(
                    selection.copy(
                        alias = "${variableName}Literal",
                        subselections = selection.subselections.take(1),
                    ),
                    selection.copy(
                        alias = "${variableName}Symbolic",
                        arguments = symbolicArguments,
                        subselections = selection.subselections.drop(1),
                    ),
                )
            }
        }
    }
}

private sealed interface InputValueStep {
    data class Field(
        val name: String,
    ) : InputValueStep

    data class Index(
        val index: Int,
    ) : InputValueStep
}

private data class InputValueOccurrence(
    val path: List<InputValueStep>,
    val target: VariableTarget,
    val existingVariableName: String?,
)

private fun InputValuePlan.variableOccurrences(
    path: List<InputValueStep> = emptyList(),
): List<InputValueOccurrence> =
    listOfNotNull(
        variableTarget()?.let { target ->
            InputValueOccurrence(
                path = path,
                target = target,
                existingVariableName = (this as? VariableInputPlan)?.variableName,
            )
        },
    ) +
        when (this) {
            is ListInputPlan ->
                elements.flatMapIndexed { index, element ->
                    element.variableOccurrences(path + InputValueStep.Index(index))
                }
            is ObjectInputPlan ->
                fields.flatMap { (name, field) ->
                    field.variableOccurrences(path + InputValueStep.Field(name))
                }
            is InputLiteralPlan,
            is ErrorInputPlan,
            is NullInputPlan,
            is VariableInputPlan,
            -> emptyList()
        }

private fun InputValuePlan.containsVariable(): Boolean =
    when (this) {
        is VariableInputPlan -> true
        is ListInputPlan -> elements.any(InputValuePlan::containsVariable)
        is ObjectInputPlan -> fields.values.any(InputValuePlan::containsVariable)
        is InputLiteralPlan,
        is ErrorInputPlan,
        is NullInputPlan,
        -> false
    }

private fun FragmentSelectionPlan.argumentVariableNames(): Set<String> =
    arguments.values.flatMapTo(linkedSetOf(), InputValuePlan::variableNames)

private fun InputValuePlan.variableNames(): Set<String> =
    when (this) {
        is VariableInputPlan -> setOf(variableName)
        is ListInputPlan -> elements.flatMapTo(linkedSetOf(), InputValuePlan::variableNames)
        is ObjectInputPlan -> fields.values.flatMapTo(linkedSetOf(), InputValuePlan::variableNames)
        is InputLiteralPlan,
        is ErrorInputPlan,
        is NullInputPlan,
        -> emptySet()
    }

private fun FragmentPlan.variableUseCount(variableName: String): Int =
    selections.sumOf { selection -> selection.variableUseCount(variableName) }

private fun FragmentSelectionPlan.variableUseCount(variableName: String): Int =
    arguments.values.sumOf { value -> value.variableUseCount(variableName) } +
        subselections.sumOf { selection -> selection.variableUseCount(variableName) }

private fun InputValuePlan.variableUseCount(variableName: String): Int =
    when (this) {
        is VariableInputPlan -> if (this.variableName == variableName) 1 else 0
        is ListInputPlan -> elements.sumOf { element -> element.variableUseCount(variableName) }
        is ObjectInputPlan ->
            fields.values.sumOf { field -> field.variableUseCount(variableName) }
        is ErrorInputPlan -> placeholder.variableUseCount(variableName)
        is InputLiteralPlan,
        is NullInputPlan,
        -> 0
    }

private fun FragmentPlan.variableTargets(variableName: String): List<VariableTarget> =
    selections.flatMap { selection -> selection.variableTargets(variableName) }

private fun FragmentSelectionPlan.variableTargets(variableName: String): List<VariableTarget> =
    arguments.values.flatMap { value -> value.variableTargets(variableName) } +
        subselections.flatMap { selection -> selection.variableTargets(variableName) }

private fun InputValuePlan.variableTargets(variableName: String): List<VariableTarget> =
    when (this) {
        is VariableInputPlan ->
            if (this.variableName == variableName) listOfNotNull(target) else emptyList()
        is ListInputPlan -> elements.flatMap { element -> element.variableTargets(variableName) }
        is ObjectInputPlan ->
            fields.values.flatMap { field -> field.variableTargets(variableName) }
        is ErrorInputPlan -> placeholder.variableTargets(variableName)
        is InputLiteralPlan,
        is NullInputPlan,
        -> emptyList()
    }

private fun FromFieldVariableProviderPlan.providerArgumentVariableNames(): Set<String> =
    selection.pathArgumentVariableNames()

private fun FragmentSelectionPlan.pathArgumentVariableNames(): Set<String> =
    buildSet {
        var current = this@pathArgumentVariableNames
        while (true) {
            addAll(current.argumentVariableNames())
            current = current.subselections.singleOrNull() ?: break
        }
    }

private fun InputTypeSpec.withOuterNullability(nullable: Boolean): InputTypeSpec =
    when (this) {
        is ScalarInputTypeSpec -> copy(nullable = nullable)
        is ListInputTypeSpec -> copy(nullable = nullable)
        is InputObjectInputTypeSpec -> copy(nullable = nullable)
    }

private fun InputValuePlan.replace(
    path: List<InputValueStep>,
    replacement: InputValuePlan,
): InputValuePlan {
    if (path.isEmpty()) return replacement
    return when (val step = path.first()) {
        is InputValueStep.Field -> {
            require(this is ObjectInputPlan)
            copy(
                fields =
                    fields +
                        (
                            step.name to
                                fields.getValue(step.name).replace(path.drop(1), replacement)
                        ),
            )
        }
        is InputValueStep.Index -> {
            require(this is ListInputPlan)
            copy(
                elements =
                    elements.mapIndexed { index, element ->
                        if (index == step.index) {
                            element.replace(path.drop(1), replacement)
                        } else {
                            element
                        }
                    },
            )
        }
    }
}

internal sealed interface ValuePlan {
    /**
     * Materializes this plan. [generatedHashSeed] may affect only synthetic [GENERATED_HASH_TYPE]
     * subtrees; equal seeds and other arguments produce equal values.
     */
    fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String? = null,
        generatedHashSeed: Int = 0,
    ): ResolverOutputData?

    fun selectedPaths(prefix: String = ""): Set<String>

    fun containsGeneratedHash(): Boolean = false
}

internal data object NullPlan : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): EngineOutputData? = null

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data object ErrorPlan : ValuePlan {
    private val error = EngineErrorData.of()

    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): EngineOutputData = error

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data object InputIdPlan : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): EngineOutputData = requireNotNull(inputId)

    override fun selectedPaths(prefix: String): Set<String> = setOf(prefix)
}

internal data class ScalarPlan(
    val scalar: ScalarKind,
    val value: Any,
) : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): EngineOutputData =
        when (scalar) {
            ScalarKind.BOOLEAN -> value as Boolean
            ScalarKind.FLOAT -> value as Double
            ScalarKind.ID -> value as String
            ScalarKind.INT -> value as Int
            ScalarKind.STRING -> value as String
        }

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data class EnumPlan(
    val value: String,
) : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): EngineOutputData = value

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data class RootFieldReferencePlan(
    val target: RootFieldReferenceTargetSpec,
) : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): RootFieldReferenceData = materializeReference(schema)

    fun materializeReference(schema: ViaductSchema): RootFieldReferenceData {
        val sourceSchema = SourceSchemaAdapter(schema)
        val path =
            target.path.map { coordinate ->
                sourceSchema.field(coordinate.typeName, coordinate.fieldName)
                    as ViaductSchema.ObjectField
            }
        return RootFieldReferenceData.of(path, target.arguments)
    }

    override fun selectedPaths(prefix: String): Set<String> = setOf(prefix)
}

internal data class ListPlan(
    val elements: List<ValuePlan>,
) : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): List<ResolverOutputData?> {
        val elementType = checkNotNull(typeExpr.unwrapList())
        return elements.map {
            it.materialize(schema, elementType, inputId, generatedHashSeed)
        }
    }

    override fun selectedPaths(prefix: String): Set<String> =
        elements.flatMapIndexed { index, element ->
            element.selectedPaths("$prefix[$index]")
        }.toSet()

    override fun containsGeneratedHash(): Boolean =
        elements.any(ValuePlan::containsGeneratedHash)
}

internal data class ObjectPlan(
    val typeName: String,
    val fields: Map<FieldCoordinate, ValuePlan>,
) : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): EngineObjectData.Sync =
        materializeObject(schema, inputId, generatedHashSeed)

    fun materializeObject(
        schema: ViaductSchema,
        inputId: String?,
        generatedHashSeed: Int = 0,
    ): EngineObjectData.Sync {
        val sourceSchema = SourceSchemaAdapter(schema)
        return schema.objectOf(typeName) {
            fields.forEach { (coordinate, plan) ->
                require(coordinate.typeName == typeName)
                val outputField = sourceSchema.field(typeName, coordinate.fieldName)
                require(outputField is ViaductSchema.ObjectField)
                field(outputField) setTo
                    plan.materialize(
                        schema,
                        sourceSchema.typeExpr(outputField),
                        inputId,
                        generatedHashSeed,
                    )
            }
        }
    }

    override fun selectedPaths(prefix: String): Set<String> =
        fields.flatMap { (coordinate, plan) ->
            val path =
                if (prefix.isEmpty()) coordinate.fieldName else "$prefix.${coordinate.fieldName}"
            setOf(path) + plan.selectedPaths(path)
        }.toSet()

    override fun containsGeneratedHash(): Boolean =
        fields.values.any(ValuePlan::containsGeneratedHash)
}

/**
 * A bounded synthetic object subtree used to make structured resolver outputs value-sensitive.
 *
 * The invocation seed is mixed with this plan's fixed [salt], so list positions and nested object
 * plans can have distinct shapes without consulting application order or mutable state. The
 * terminal object omits its nullable `nested` field.
 */
internal data class GeneratedHashPlan(
    val salt: Int,
) : ValuePlan {
    override fun materialize(
        schema: ViaductSchema,
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        inputId: String?,
        generatedHashSeed: Int,
    ): EngineObjectData.Sync {
        require(typeExpr.baseTypeDef.name == GENERATED_HASH_TYPE)
        val rootHash = mixGeneratedHash(generatedHashSeed, salt)
        return generatedHashObject(
            schema = schema,
            hash = rootHash,
            remainingDepth = depth(generatedHashSeed),
        )
    }

    internal fun depth(generatedHashSeed: Int = 0): Int =
        Math.floorMod(
            mixGeneratedHash(generatedHashSeed, salt),
            MAX_GENERATED_HASH_DEPTH + 1,
        )

    override fun selectedPaths(prefix: String): Set<String> =
        (0..MAX_GENERATED_HASH_DEPTH).flatMapTo(linkedSetOf()) { depth ->
            val nestedPrefix =
                (0 until depth).fold(prefix) { path, _ ->
                    "$path.$GENERATED_HASH_NESTED_FIELD"
                }
            listOf(nestedPrefix, "$nestedPrefix.$GENERATED_HASH_FIELD")
        }

    override fun containsGeneratedHash(): Boolean = true
}

// Makes the dedicated random-parent scalar resolvers reliable diagonal witnesses.
private const val RANDOM_PARENT_DIAGONAL_RESOLVER_WEIGHT = 1.0
private const val MAX_GENERATED_HASH_DEPTH = 4
private const val GENERATED_HASH_NESTED_SALT = -1640531527

private fun List<FieldCoordinate>.withGeneratedRandomParentResolverOrder(
    randomParentFieldsEnabled: Boolean,
): List<FieldCoordinate> {
    if (!randomParentFieldsEnabled) return this
    val directedResolvers =
        filter { coordinate ->
            coordinate.typeName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX) &&
                coordinate.fieldName == "value0"
        }.sortedBy(FieldCoordinate::typeName)
    val orderedResolvers = directedResolvers.iterator()
    return map { coordinate ->
        if (coordinate in directedResolvers) orderedResolvers.next() else coordinate
    }
}

internal fun List<FieldCoordinate>.withGeneratedParentResultAfterAncestor(
    parentFieldsEnabled: Boolean,
): List<FieldCoordinate> {
    if (!parentFieldsEnabled) return this
    val ancestor = FieldCoordinate(GENERATED_PARENT_ROOT_TYPE, GENERATED_PARENT_VALUE_FIELD)
    val result =
        FieldCoordinate(
            GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
            GENERATED_PARENT_RESULT_FIELD,
        )
    if (indexOf(ancestor) < indexOf(result)) return this

    return toMutableList().apply {
        remove(result)
        add(indexOf(ancestor) + 1, result)
    }
}

private fun generatedHashObject(
    schema: ViaductSchema,
    hash: Int,
    remainingDepth: Int,
): EngineObjectData.Sync =
    schema.objectOf(GENERATED_HASH_TYPE) {
        GENERATED_HASH_FIELD setTo hash
        if (remainingDepth > 0) {
            GENERATED_HASH_NESTED_FIELD setTo
                generatedHashObject(
                    schema = schema,
                    hash = mixGeneratedHash(hash, GENERATED_HASH_NESTED_SALT),
                    remainingDepth = remainingDepth - 1,
                )
        }
    }

private fun mixGeneratedHash(
    hash: Int,
    value: Int,
): Int = hash * 31 + value

private fun stableGeneratedHash(vararg components: String): Int =
    components.fold(1) { result, component ->
        component.fold(result * 31 + component.length) { hash, character ->
            mixGeneratedHash(hash, character.code)
        }
    }
