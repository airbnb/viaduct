package viaduct.arbitrary.graphql

import graphql.introspection.Introspection
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.Selection as GJSelection
import graphql.language.SelectionSet
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.instant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.localTime
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.short
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.zoneOffset
import java.time.OffsetTime
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.ConfigKey
import viaduct.engine.api.ViaductSchema
import viaduct.mapping.graphql.IR

/**
 * Return an [Arb] that can generate an [IR.Value.Object] for
 * an input or output type in the provided schema.
 *
 * @param cfg Configuration to shape the generated value. This method knows how to handle these [ConfigKey]s:
 *   - [OutputObjectValueWeight]
 *   - [InputObjectValueWeight]
 *   - [IntrospectionObjectValueWeight]
 *   - [ListValueSize]
 *   - [ImplicitNullValueWeight]
 *   - [ExplicitNullValueWeight]
 *   - [MaxValueDepth]
 *   - [TypenameValueWeight]
 */
fun Arb.Companion.objectIR(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<IR.Value.Object> =
    arbitrary { rs ->
        IRGen(schema, cfg, rs).genObjectValue()
    }

/**
 * Return an [Arb] that can generate an [IR.Value.Object] for arbitrary output objects
 * in the provided schema.
 *
 * @param cfg see docs for [Arb.Companion.objectIR] for a list of support [ConfigKey]s
 */
fun Arb.Companion.outputObjectIR(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<IR.Value.Object> = objectIR(schema, cfg + (OutputObjectValueWeight to 1.0) + (InputObjectValueWeight to 0.0))

/**
 * Return an [Arb] that can generate an [IR.Value.Object] for arbitrary input objects
 * in the provided schema.
 *
 * @param cfg see docs for [Arb.Companion.objectIR] for a list of support [ConfigKey]s
 */
fun Arb.Companion.inputObjectIR(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<IR.Value.Object> = objectIR(schema, cfg + (OutputObjectValueWeight to 0.0) + (InputObjectValueWeight to 1.0))

/**
 * Return an [Arb] that can generate an [IR.Value] for the provided type defined in the
 * provided schema.
 *
 * @param cfg see docs for [Arb.Companion.objectIR] for a list of support [ConfigKey]s
 */
fun Arb.Companion.ir(
    schema: ViaductSchema,
    type: GraphQLType,
    cfg: Config = Config.default
): Arb<IR.Value> =
    arbitrary { rs ->
        IRGen(schema, cfg, rs).genValue(type)
    }

/**
 * Return an [Arb] that can generate an [IR.Value] for the provided output type
 * and selections.
 */
fun Arb.Companion.ir(
    schema: ViaductSchema,
    type: GraphQLOutputType,
    selections: SelectionSet?,
    fragments: Map<String, FragmentDefinition> = emptyMap(),
    cfg: Config = Config.default
): Arb<IR.Value> =
    arbitrary { rs ->
        IRResultGen(schema, fragments, cfg, rs).genValue(type, selections)
    }

/** Return an [Arb] that can generate an [IR.Value] for the provided [Document] */
fun Arb.Companion.ir(
    schema: ViaductSchema,
    document: Document,
    cfg: Config = Config.default
): Arb<IR.Value> {
    val fragments = mutableMapOf<String, FragmentDefinition>()
    val operations = mutableListOf<OperationDefinition>()
    document.definitions.forEach {
        when (it) {
            is FragmentDefinition -> fragments[it.name] = it
            is OperationDefinition -> operations += it
        }
    }

    return Arb.of(operations).flatMap {
        val type = when (it.operation) {
            OperationDefinition.Operation.QUERY -> schema.schema.queryType
            OperationDefinition.Operation.MUTATION ->
                checkNotNull(schema.schema.mutationType) {
                    "mutation operation requested but schema does not define a mutation type"
                }
            OperationDefinition.Operation.SUBSCRIPTION ->
                checkNotNull(schema.schema.subscriptionType) {
                    "subscription operation requested but schema does not define a subscription type"
                }
            null -> throw IllegalStateException("Operation can't be null")
        }

        ir(schema, GraphQLNonNull(type), it.selectionSet, fragments, cfg)
    }
}

data class TypeCtx(
    val type: GraphQLType,
    val field: GraphQLDirectiveContainer? = null,
    val fieldParent: GraphQLType? = null
) {
    val appliedDirectives: List<GraphQLAppliedDirective>
        get() = this.field?.appliedDirectives ?: emptyList()

    fun traverse(field: GraphQLFieldDefinition): TypeCtx {
        require(type is GraphQLFieldsContainer)
        return TypeCtx(field.type, field, type)
    }

    fun traverse(field: GraphQLInputObjectField): TypeCtx {
        require(type is GraphQLInputObjectType)
        return TypeCtx(field.type, field, type)
    }

    /** replace the current type, without changing the parent or field */
    fun traverse(type: GraphQLType): TypeCtx = copy(type = type)
}

private class IRGen(
    private val schema: ViaductSchema,
    private val cfg: Config,
    private val rs: RandomSource,
) {
    private val enumGen = EnumValueGen(rs)
    private val scalarGen = ScalarValueGen(cfg, rs)
    private val cyclicInputSCCs: Map<String, Set<String>> = mkCyclicInputSCCs(schema.schema)

    private data class Ctx(
        val tc: TypeCtx,
        val depth: Int,
        val maxDepth: Int,
        val nonNullable: Boolean = false,
    ) {
        fun traverse(field: GraphQLInputObjectField): Ctx = push(tc.traverse(field))

        fun traverse(field: GraphQLFieldDefinition): Ctx = push(tc.traverse(field))

        private fun push(type: TypeCtx): Ctx = copy(tc = type, depth = depth + 1, nonNullable = type.type is GraphQLNonNull)

        val nullable: Boolean get() = !nonNullable
        val overBudget: Boolean get() = depth > maxDepth
    }

    private val graphQLObjectishTypeArb: Arb<GraphQLType>

    init {
        val nonIntrospectionObjectTypes = mutableListOf<GraphQLObjectType>()
        val introspectionObjectTypes = mutableListOf<GraphQLObjectType>()
        val nonIntrospectionInputObjectTypes = mutableListOf<GraphQLInputObjectType>()

        schema.schema.allTypesAsList.forEach { t ->
            when (t) {
                is GraphQLObjectType -> {
                    if (Introspection.isIntrospectionTypes(t)) {
                        introspectionObjectTypes += t
                    } else {
                        nonIntrospectionObjectTypes += t
                    }
                }
                is GraphQLInputObjectType -> {
                    nonIntrospectionInputObjectTypes += t
                }
                else -> {}
            }
        }

        val weightedPools = listOf(
            cfg[OutputObjectValueWeight] to nonIntrospectionObjectTypes,
            (cfg[OutputObjectValueWeight] * cfg[IntrospectionObjectValueWeight]) to introspectionObjectTypes,
            cfg[InputObjectValueWeight] to nonIntrospectionInputObjectTypes,
        )
        val weightedArbs = weightedPools
            .filter { it.second.isNotEmpty() }
            .map { (weight, pool) -> weight to Arb.of(pool) }

        graphQLObjectishTypeArb = Arb.weightedChoose(weightedArbs)
    }

    fun genObjectValue(): IR.Value.Object {
        val objType = graphQLObjectishTypeArb.next(rs)
        return genValue(GraphQLNonNull.nonNull(objType)) as IR.Value.Object
    }

    fun genValue(type: GraphQLType): IR.Value = genValue(Ctx(tc = TypeCtx(type), depth = 0, maxDepth = cfg[MaxValueDepth]))

    private fun genValue(ctx: Ctx): IR.Value =
        with(ctx) {
            when {
                tc.type is GraphQLNonNull -> {
                    genValue(
                        ctx.copy(
                            tc = tc.traverse(tc.type.wrappedType),
                            nonNullable = true
                        )
                    )
                }

                ctx.nullable && (overBudget || rs.sampleWeight(cfg[ExplicitNullValueWeight])) -> {
                    IR.Value.Null
                }

                tc.type is GraphQLList -> {
                    val newCtx = copy(tc = tc.traverse(tc.type.wrappedType), depth = depth + 1)
                    // return early if overbudget
                    val listSize = if (newCtx.overBudget) 0 else Arb.int(cfg[ListValueSize]).next(rs)
                    val values = buildList(listSize) {
                        repeat(listSize) {
                            add(genValue(newCtx))
                        }
                    }
                    IR.Value.List(values)
                }

                tc.type is GraphQLScalarType -> scalarGen.gen(tc.type)

                tc.type is GraphQLEnumType -> enumGen.gen(tc.type)

                tc.type is GraphQLInputObjectType && tc.type.isOneOf -> {
                    val field = Arb.of(tc.type.fields).next(rs)
                    val fieldValue = genValue(traverse(field).copy(nonNullable = true))
                    IR.Value.Object(tc.type.name, field.name to fieldValue)
                }

                tc.type is GraphQLInputObjectType -> {
                    val scc = cyclicInputSCCs[tc.type.name]
                    val nameValuePairs = tc.type.fields
                        .filter { f ->
                            val fieldTypeName = (GraphQLTypeUtil.unwrapAll(f.type) as? GraphQLInputObjectType)?.name

                            if (scc != null && fieldTypeName != null && fieldTypeName in scc) {
                                // if the type of this field is in the SCC of the current input object type, then
                                // there is a cyclic relationship between the the fields type and the current input object type.
                                // To comply with the spec rule 'InputObjectDefaultValueHasCycle', we cannot omit a value for
                                // these fields
                                // Returning true here ensures that these fields are kept in the set of fields for which we will
                                // generate values
                                true
                            } else if (!f.hasSetDefaultValue() && GraphQLTypeUtil.isNonNull(f.type)) {
                                // field is non-nullable and has no default.
                                // Keep the field to ensure that a value is generated
                                true
                            } else {
                                // If we get to this case, the field either has a default value or it is nullable.
                                // Sample ImplicitNullValueWeight to either keep or drop
                                !rs.sampleWeight(cfg[ImplicitNullValueWeight])
                            }
                        }.map { f ->
                            f.name to genValue(traverse(f))
                        }
                    IR.Value.Object(tc.type.name, nameValuePairs.toMap())
                }

                tc.type is GraphQLObjectType -> {
                    val fieldValues = tc.type.fields
                        .filterNot { overBudget || rs.sampleWeight(cfg[ImplicitNullValueWeight]) }
                        .associate { f -> f.name to genValue(traverse(f)) }

                    val typenameValue = if (rs.sampleWeight(cfg[TypenameValueWeight])) {
                        mapOf("__typename" to IR.Value.String(tc.type.name))
                    } else {
                        emptyMap()
                    }

                    IR.Value.Object(tc.type.name, fieldValues + typenameValue)
                }

                tc.type is GraphQLCompositeType -> {
                    val impls = schema.rels.possibleObjectTypes(tc.type).toList()
                    require(impls.isNotEmpty()) {
                        "Cannot generate a value for abstract type: ${tc.type.name}: no implementations found"
                    }
                    val impl = Arb.of(impls).next(rs)
                    genValue(copy(tc = tc.traverse(impl)))
                }

                else -> throw UnsupportedOperationException("Unsupported type: $tc")
            }
        }
}

private class IRResultGen(
    private val schema: ViaductSchema,
    private val fragments: Map<String, FragmentDefinition>,
    private val cfg: Config,
    private val rs: RandomSource,
) {
    private val enumGen = EnumValueGen(rs)
    private val scalarGen = ScalarValueGen(cfg, rs)

    fun genValue(
        type: GraphQLOutputType,
        selections: SelectionSet?
    ): IR.Value = gen(type, selections, true)

    private fun enullOr(
        nullable: Boolean,
        fn: () -> IR.Value
    ) = if (nullable && rs.sampleWeight(cfg[ExplicitNullValueWeight])) {
        IR.Value.Null
    } else {
        fn()
    }

    private fun gen(
        type: GraphQLOutputType,
        selections: SelectionSet?,
        nullable: Boolean
    ): IR.Value =
        when (type) {
            is GraphQLNonNull -> gen(GraphQLTypeUtil.unwrapOneAs(type), selections, false)
            is GraphQLList ->
                enullOr(nullable) {
                    val listSize = Arb.int(cfg[ListValueSize]).next(rs)
                    val items = List(listSize) {
                        gen(GraphQLTypeUtil.unwrapOneAs(type), selections, true)
                    }
                    IR.Value.List(items)
                }

            is GraphQLObjectType ->
                enullOr(nullable) {
                    genObject(type, requireNotNull(selections))
                }

            is GraphQLCompositeType ->
                enullOr(nullable) {
                    val concreteType = concretizeType(type, requireNotNull(selections))
                    gen(concreteType, selections, nullable)
                }

            is GraphQLEnumType ->
                enullOr(nullable) {
                    enumGen.gen(type)
                }

            is GraphQLScalarType ->
                enullOr(nullable) {
                    scalarGen.gen(type)
                }

            else -> throw IllegalArgumentException("Unsupported type: $type")
        }

    private fun genObject(
        type: GraphQLCompositeType,
        selections: SelectionSet
    ): IR.Value.Object {
        val concreteType = concretizeType(type, selections)

        return selections.selections.fold(IR.Value.Object(concreteType.name)) { acc, sel ->
            when (sel) {
                is Field -> {
                    if (sel.name == "__typename") {
                        acc + (sel.resultKey to IR.Value.String(concreteType.name))
                    } else {
                        val fieldDef = requireNotNull(concreteType.getField(sel.name)) {
                            "unexpected field: ${concreteType.name}.${sel.name}"
                        }
                        val value = gen(fieldDef.type, sel.selectionSet, true)
                        acc + (sel.resultKey to value)
                    }
                }

                is FragmentSpread -> {
                    val fragment = requireNotNull(fragments[sel.name]) { "missing fragment `${sel.name}`" }
                    val fragmentType = schema.schema.getTypeAs<GraphQLCompositeType>(fragment.typeCondition.name)
                    if (schema.rels.isSpreadable(concreteType, fragmentType)) {
                        val fragmentResult = genObject(concreteType, fragment.selectionSet)
                        acc.copy(fields = acc.fields + fragmentResult.fields)
                    } else {
                        acc
                    }
                }

                is InlineFragment -> {
                    val fragmentType = sel.typeCondition?.name
                        ?.let { schema.schema.getTypeAs<GraphQLCompositeType>(it) }
                        ?: concreteType
                    if (schema.rels.isSpreadable(concreteType, fragmentType)) {
                        val fragmentResult = genObject(concreteType, sel.selectionSet)
                        acc.copy(fields = acc.fields + fragmentResult.fields)
                    } else {
                        acc
                    }
                }

                else -> throw IllegalArgumentException("unexpected selection type: $sel")
            }
        }
    }

    /** Pick a concrete object type for the supplied type */
    private fun concretizeType(
        type: GraphQLCompositeType,
        selections: SelectionSet
    ): GraphQLObjectType {
        if (type is GraphQLObjectType) return type

        var candidateTypes = listOf<GraphQLObjectType>()
        if (rs.sampleWeight(cfg[SelectedTypeBias])) {
            val concreteCandidates = selectedObjectTypes(selections)
            if (concreteCandidates.isNotEmpty()) {
                candidateTypes = concreteCandidates.toList()
            }
        }
        if (candidateTypes.isEmpty()) {
            candidateTypes = schema.rels.possibleObjectTypes(type).toList()
        }
        return Arb.of(candidateTypes).next(rs)
    }

    /**
     * Return the set of GraphQLObject types that have type conditions within a selection set.
     * This will traverse through fragment definitions and inline fragments, but not through field selections
     */
    private fun selectedObjectTypes(selections: SelectionSet): Set<GraphQLObjectType> {
        tailrec fun loop(
            acc: Set<GraphQLObjectType>,
            pending: List<GJSelection<*>>
        ): Set<GraphQLObjectType> =
            when (val sel = pending.firstOrNull()) {
                null -> acc
                is Field -> loop(acc, pending.drop(1))
                is InlineFragment -> {
                    val typeCondition = sel.typeCondition?.name?.let { schema.schema.getTypeAs<GraphQLCompositeType>(it) }
                    val newAcc = (typeCondition as? GraphQLObjectType)?.let { acc + it } ?: acc
                    val newPending = pending.drop(1) + sel.selectionSet.selections
                    loop(newAcc, newPending)
                }
                is FragmentSpread -> {
                    val fragment = requireNotNull(this.fragments[sel.name]) {
                        "missing fragment `${sel.name}`"
                    }
                    val typeCondition = schema.schema.getTypeAs<GraphQLCompositeType>(fragment.typeCondition.name)
                    val newAcc = (typeCondition as? GraphQLObjectType)?.let { acc + it } ?: acc
                    val newPending = pending.drop(1) + fragment.selectionSet.selections
                    loop(newAcc, newPending)
                }
                else -> throw IllegalArgumentException("unexpected selection type: $sel")
            }

        return loop(emptySet(), selections.selections)
    }
}

internal operator fun IR.Value.Object.plus(entry: Pair<String, IR.Value>): IR.Value.Object = copy(fields = fields + entry)

private class EnumValueGen(private val rs: RandomSource) {
    fun gen(type: GraphQLEnumType): IR.Value.String = Arb.of(type.values).next(rs).let { IR.Value.String(it.name) }
}

private class ScalarValueGen(
    private val cfg: Config,
    private val rs: RandomSource
) {
    fun gen(type: GraphQLScalarType): IR.Value {
        val arbOverride = cfg[ScalarValueOverrides][type.name]
        if (arbOverride != null) {
            return arbOverride.next(rs)
        }

        return when (type.name) {
            "BackingData" ->
                IR.Value.Null

            "Boolean" -> IR.Value.Boolean(Arb.boolean().next(rs))
            "Byte" -> IR.Value.Number(Arb.byte().next(rs))
            "Date" -> IR.Value.Time(Arb.localDate().next(rs))
            "DateTime" -> IR.Value.Time(Arb.instant().next(rs))
            "Float" -> IR.Value.Number(Arb.double().next(rs))
            "ID" -> IR.Value.String(Arb.string(cfg[StringValueSize]).next(rs))
            "Int" -> IR.Value.Number(Arb.int().next(rs))
            "JSON" -> IR.Value.String("{}")
            "Long" -> IR.Value.Number(Arb.long().next(rs))
            "Short" -> IR.Value.Number(Arb.short().next(rs))
            "String" -> IR.Value.String(Arb.string(cfg[StringValueSize]).next(rs))
            "Time" ->
                Arb
                    .bind(Arb.localTime(), Arb.zoneOffset(), OffsetTime::of)
                    .next(rs)
                    .let(IR.Value::Time)

            else -> throw UnsupportedOperationException("Unsupported scalar type: ${type.name}")
        }
    }
}

/**
 * Build the strongly connected components graph for input objects in the provided schema.
 * Returns a map from type name to the set of type names in its SCC,
 * but only for types in cyclic SCCs (size > 1 or has a self-loop).
 * Types not in any cycle are omitted from the map.
 */
internal fun mkCyclicInputSCCs(schema: GraphQLSchema): Map<String, Set<String>> {
    val inputTypes = schema.allTypesAsList.filterIsInstance<GraphQLInputObjectType>()
    if (inputTypes.isEmpty()) return emptyMap()

    // Build adjacency list: edge from A to B if A has a field whose unwrapped type is input type B
    val adj = mutableMapOf<String, MutableSet<String>>()
    val selfLoops = mutableSetOf<String>()
    for (type in inputTypes) {
        val neighbors = mutableSetOf<String>()
        for (field in type.fields) {
            val unwrapped = GraphQLTypeUtil.unwrapAll(field.type)
            if (unwrapped is GraphQLInputObjectType) {
                if (unwrapped.name == type.name) {
                    selfLoops.add(type.name)
                }
                neighbors.add(unwrapped.name)
            }
        }
        adj[type.name] = neighbors
    }

    // Tarjan's SCC algorithm
    var index = 0
    val indices = mutableMapOf<String, Int>()
    val lowlinks = mutableMapOf<String, Int>()
    val onStack = mutableSetOf<String>()
    val stack = ArrayDeque<String>()
    val sccs = mutableListOf<Set<String>>()

    fun strongconnect(v: String) {
        indices[v] = index
        lowlinks[v] = index
        index++
        stack.addLast(v)
        onStack.add(v)

        for (w in adj[v] ?: emptySet()) {
            if (w !in indices) {
                strongconnect(w)
                lowlinks[v] = minOf(lowlinks[v]!!, lowlinks[w]!!)
            } else if (w in onStack) {
                lowlinks[v] = minOf(lowlinks[v]!!, indices[w]!!)
            }
        }

        if (lowlinks[v] == indices[v]) {
            val scc = mutableSetOf<String>()
            do {
                val w = stack.removeLast()
                onStack.remove(w)
                scc.add(w)
            } while (w != v)
            sccs.add(scc)
        }
    }

    for (v in adj.keys) {
        if (v !in indices) {
            strongconnect(v)
        }
    }

    // Only keep cyclic SCCs: size > 1, or size 1 with self-loop
    val result = mutableMapOf<String, Set<String>>()
    for (scc in sccs) {
        if (scc.size > 1 || (scc.size == 1 && scc.first() in selfLoops)) {
            for (name in scc) {
                result[name] = scc
            }
        }
    }
    return result
}
