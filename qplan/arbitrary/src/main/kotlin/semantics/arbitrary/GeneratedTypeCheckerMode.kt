package semantics.arbitrary

import model.SelectionForest
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectKey
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.graphql.schema.ViaductSchema

/** Execution-profile input; ordinary generated worlds remain type-checker free. */
enum class GeneratedTypeCheckerMode { NONE, SUCCESS, DENIAL, MIXED }

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
            TypeCheckerResolver.of(
                type,
                query,
                mapOf(
                    "left" to pair,
                    "right" to pair,
                    "empty" to ResolverFragmentTemplates(materializeSelectionForestOf(), materializeSelectionForestOf()),
                )
            ) { inputs, _ ->
                val left = inputs.getValue("left")
                val right = inputs.getValue("right")
                check(left.objectValue.get("kind") == type.name)
                check(left.queryValue.get("rootKind") == "Query")
                check(left.objectValue.resolutionFingerprint() == right.objectValue.resolutionFingerprint())
                check(left.queryValue.resolutionFingerprint() == right.queryValue.resolutionFingerprint())
                when (mode) {
                    GeneratedTypeCheckerMode.SUCCESS -> CheckerResult.Success
                    GeneratedTypeCheckerMode.DENIAL -> GeneratedTypeCheckerDenial
                    GeneratedTypeCheckerMode.MIXED ->
                        if (type.name.hashCode() % 2 == 0) CheckerResult.Success else GeneratedTypeCheckerDenial
                    GeneratedTypeCheckerMode.NONE -> error("No generated type checker was requested")
                }
            }
        }
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
