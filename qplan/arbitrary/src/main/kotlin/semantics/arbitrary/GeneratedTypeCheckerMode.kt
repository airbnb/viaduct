package semantics.arbitrary

import model.Arguments
import model.MaterializeSelectionForest
import model.SelectionForest
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectKey
import model.registry.ProviderFragment
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.registry.TypeCheckerResolver
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.usedVariables
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.graphql.schema.ViaductSchema

/** Execution-profile input; ordinary generated worlds remain type-checker free. */
enum class GeneratedTypeCheckerMode(val runtimeVariables: Boolean = false) {
    NONE,
    SUCCESS,
    DENIAL,
    MIXED,
    RUNTIME_SUCCESS(true),
    RUNTIME_DENIAL(true),
    RUNTIME_MIXED(true),
}

/**
 * Grounded type checks may demand scalar value resolvers on either root. A resolver is eligible
 * only when every type reached by its transitive checked inputs precedes the checked type by name.
 * Combined with the generator's field dependency DAG, this orders the added type/value edges too.
 * Fragment roots are local field demand, not type demand; composite-valued selections are type
 * demand even when their child forest is empty. Field checkers reuse the same resolver fragments.
 */
internal fun ArbitraryRegistry.generatedTypeCheckers(
    schema: ViaductSchema,
    mode: GeneratedTypeCheckerMode,
): Map<ViaductSchema.Object, TypeCheckerResolver> {
    if (mode == GeneratedTypeCheckerMode.NONE) return emptyMap()
    val query = schema.requireQueryTypeDef()
    val dependencies = TypeCheckerResolverDependencies(this, schema)
    return schema.types.values.filterIsInstance<ViaductSchema.Object>()
        .filter { it != query && !it.name.startsWith("__") }
        .sortedBy { it.name }
        .associateWith { type ->
            val passiveFields = type.fields.filter { field ->
                field.args.isEmpty() && field.type.baseTypeDef is ViaductSchema.SimpleTypeDef &&
                    !field.name.contains("V_A") && !field.name.startsWith("__") &&
                    FieldCoordinate(type.name, field.name) !in fieldResolverCoordinates
            }.sortedBy { it.name }.take(2)

            fun activeFields(owner: ViaductSchema.Object): List<ViaductSchema.ObjectField> =
                owner.fields.filter { field ->
                    field.args.isEmpty() && field.type.baseTypeDef is ViaductSchema.SimpleTypeDef &&
                        FieldCoordinate(owner.name, field.name) in fieldResolverCoordinates &&
                        dependencies.checkedTypes(FieldCoordinate(owner.name, field.name)).all { it < type.name }
                }.sortedBy { it.name }.take(2)
            val objectInput = "kind: __typename " +
                (passiveFields + activeFields(type)).mapIndexed { index, field -> "raw$index: ${field.name}" }.joinToString(" ")
            val queryInput = "rootKind: __typename " +
                activeFields(query).mapIndexed { index, field -> "query$index: ${field.name}" }.joinToString(" ")
            val pair = ResolverFragmentTemplates(
                schema.fragmentFrom("fragment Input on ${type.name} { $objectInput }").materializeSelections,
                schema.fragmentFrom("fragment Input on Query { $queryInput }").materializeSelections,
            )
            // Reuse sampled fragment/provider plans, just as field checkers do, but give every
            // variable the type-checker target. No field argument tuple belongs to this owner.
            val runtimePairs = if (mode.runtimeVariables) {
                fieldResolverCoordinates.filter { coordinate ->
                    val providers = variableProviders.filter { it.owner == coordinate }
                    coordinate.typeName == type.name && providers.isNotEmpty() &&
                        providers.none { it is FromArgumentVariableProviderPlan } &&
                        dependencies.checkedTypes(coordinate).all { it < type.name }
                }.sortedByDescending { coordinate ->
                    // Callback-only plans are plentiful; retain rarer path-bearing sampled plans.
                    variableProviders.count { it.owner == coordinate && it is FromFieldVariableProviderPlan }
                }.take(2).map { coordinate -> runtimeTypeCheckerPair(schema, type, coordinate) }
            } else {
                emptyList()
            }
            val pairs = listOf(pair) + runtimePairs
            TypeCheckerResolver.of(
                type,
                query,
                buildMap {
                    pairs.forEachIndexed { index, templates ->
                        put(if (index == 0) "left" else "left$index", templates)
                        put(if (index == 0) "right" else "right$index", templates)
                    }
                    put("empty", ResolverFragmentTemplates(materializeSelectionForestOf(), materializeSelectionForestOf()))
                },
            ) { inputs, _ ->
                val left = inputs.getValue("left")
                check(left.objectValue.get("kind") == type.name)
                check(left.queryValue.get("rootKind") == "Query")
                pairs.indices.forEach { index ->
                    val first = inputs.getValue(if (index == 0) "left" else "left$index")
                    val second = inputs.getValue(if (index == 0) "right" else "right$index")
                    check(first.objectValue.resolutionFingerprint() == second.objectValue.resolutionFingerprint())
                    check(first.queryValue.resolutionFingerprint() == second.queryValue.resolutionFingerprint())
                }
                when (mode) {
                    GeneratedTypeCheckerMode.SUCCESS, GeneratedTypeCheckerMode.RUNTIME_SUCCESS -> CheckerResult.Success
                    GeneratedTypeCheckerMode.DENIAL, GeneratedTypeCheckerMode.RUNTIME_DENIAL -> GeneratedTypeCheckerDenial
                    GeneratedTypeCheckerMode.MIXED, GeneratedTypeCheckerMode.RUNTIME_MIXED ->
                        if (type.name.hashCode() % 2 == 0) CheckerResult.Success else GeneratedTypeCheckerDenial
                    GeneratedTypeCheckerMode.NONE -> error("No generated type checker was requested")
                }
            }
        }
}

private fun ArbitraryRegistry.runtimeTypeCheckerPair(
    schema: ViaductSchema,
    type: ViaductSchema.Object,
    coordinate: FieldCoordinate,
): ResolverFragmentTemplates {
    val field = schema.requireObjectField(coordinate.typeName, coordinate.fieldName)
    val target = ResolverTarget.TypeCheckerTarget(type)
    val objectInput = objectFragments.getValue(coordinate).materialize(schema, field, target).materializeSelections
    val queryInput = queryFragments.getValue(coordinate).materialize(schema, field, target).materializeSelections
    val usedNames = (objectInput.constructionSelections().usedVariables() + queryInput.constructionSelections().usedVariables())
        .mapTo(linkedSetOf(), Arguments.Variable::variableName)
    val providers = variableProviders.filter { it.owner == coordinate && it.variableName in usedNames }
    val variables = providers.associate { provider ->
        val definition = when (provider) {
            is FromProviderVariableProviderPlan -> VariableDefinition.FromProvider
            is FromArgumentVariableProviderPlan -> error("Type checkers have no field arguments")
            is FromFieldVariableProviderPlan -> {
                var selections: MaterializeSelectionForest = if (provider.providerFragment == ProviderFragment.OBJECT) objectInput else queryInput
                val path = provider.responsePath().map { responseKey ->
                    val selected = selections.filter { it.responseKey == responseKey }
                    val keys = mutableListOf<model.ObjectEngineResult.Key>()
                    selected.forEach { keys += it.key }
                    val key = keys.first()
                    check(selected.all { it.key == key })
                    selections = selected.flatMap { it.subselections }
                    key
                }
                VariableDefinition.FromField.of(provider.providerFragment, path, provider.responsePath())
            }
        }
        Arguments.Variable.of(target, provider.variableName) to definition
    }
    val providerPlans = providers.filterIsInstance<FromProviderVariableProviderPlan>()
    return ResolverFragmentTemplates(
        objectFragmentTemplate = objectInput,
        queryFragmentTemplate = queryInput,
        variables = variables,
        variablesProvider = if (providerPlans.isEmpty()) {
            null
        } else {
            { arguments ->
                // Type-checker callbacks receive the empty tuple, never the sampled field's arguments.
                providerPlans.associate { it.variableName to it.value(arguments, field) }
            }
        },
    )
}

private object GeneratedTypeCheckerDenial : CheckerResult.Error {
    override val error = IllegalStateException("generated type checker denial")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

/** Conservative transitive type demand, independent of query selection and execution witnesses. */
private class TypeCheckerResolverDependencies(
    private val registry: ArbitraryRegistry,
    private val schema: ViaductSchema,
) {
    private val cache = mutableMapOf<FieldCoordinate, Set<String>>()
    private val visiting = mutableSetOf<FieldCoordinate>()

    fun checkedTypes(coordinate: FieldCoordinate): Set<String> {
        cache[coordinate]?.let { return it }
        check(visiting.add(coordinate)) { "Generated resolver dependency cycle at $coordinate" }
        val field = schema.requireObjectField(coordinate.typeName, coordinate.fieldName)
        val types = linkedSetOf<String>()

        fun collect(forest: SelectionForest) {
            forest.forEach { selection ->
                selection.possibleTypes.forEach { owner ->
                    val selected = selection.objectKey(owner).field
                    (selected.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes
                        ?.mapTo(types) { it.name }
                    val dependency = FieldCoordinate(owner.name, selected.name)
                    if (dependency in registry.fieldResolverCoordinates) types += checkedTypes(dependency)
                }
                collect(selection.subselections)
            }
        }
        collect(registry.objectFragments.getValue(coordinate).materialize(schema, field).subselections)
        collect(registry.queryFragments.getValue(coordinate).materialize(schema, field).subselections)
        visiting.remove(coordinate)
        cache[coordinate] = types
        return types
    }
}
