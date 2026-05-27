package viaduct.arbitrary.graphql

import graphql.introspection.Introspection
import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
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
import graphql.schema.GraphQLScalarType
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
 * Values returned by this generator will never require coercion to match the
 * type for which the value was generated.
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
): Arb<IR.Value.Object> {
    val mandatoryEdgesGraph = CycleGroups.mandatoryInputCycles(schema)
    return arbitrary { rs ->
        IRGen(schema, mandatoryEdgesGraph, mandatoryEdgesGraph, 0.0, cfg, rs).genObjectValue()
    }
}

/**
 * Return an [Arb] that can generate an [IR.Value.Object] for arbitrary output objects
 * in the provided schema.
 *
 * Values returned by this generator will never require coercion to match the
 * type for which the value was generated.
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
 * Values returned by this generator will never require coercion to match the
 * type for which the value was generated.
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
 * Values returned by this generator will never require coercion to match the
 * type for which the value was generated.
 *
 * @param cfg see docs for [Arb.Companion.objectIR] for a list of support [ConfigKey]s
 */
fun Arb.Companion.ir(
    schema: ViaductSchema,
    type: GraphQLType,
    cfg: Config = Config.default,
): Arb<IR.Value> {
    val mandatoryEdgesGraph = CycleGroups.mandatoryInputCycles(schema)
    return arbitrary { rs ->
        IRGen(schema, mandatoryEdgesGraph, mandatoryEdgesGraph, 0.0, cfg, rs).genValue(type)
    }
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

internal class IRGen(
    private val schema: ViaductSchema,
    // SCC graph used to decide which input fields must be retained in the non-oneOf branch
    // to preserve well-formedness. Callers may supply either [CycleGroups.mandatoryInputCycles]
    // (default) or [CycleGroups.allInputCycles] (e.g. [AddDefaults], which needs finite
    // nested-default values even through nullable cycles).
    private val allEdgesGraph: CycleGroups,
    // SCC graph of strictly mandatory edges, used in the oneOf branch. Only mandatory edges
    // can force divergence — nullable/list-wrapped cycle edges terminate naturally via the
    // `nullable && overBudget -> null` guard or via empty-list generation.
    private val mandatoryEdgesGraph: CycleGroups,
    /** The probability that a generated value will require coercion */
    private val uncoercedValueWeight: Double,
    private val cfg: Config,
    private val rs: RandomSource,
) {
    private val enumGen = EnumValueGen(rs)
    private val scalarGen = ScalarValueGen(uncoercedValueWeight, cfg, rs)

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
        val overBudget: Boolean get() = depth >= maxDepth
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

                    if (rs.sampleWeight(uncoercedValueWeight) && !newCtx.overBudget) {
                        // List types support coercing non-list values.
                        // In these cases, generate an IR value corresponding to the inner type
                        // See https://spec.graphql.org/draft/#sec-List
                        genValue(newCtx)
                    } else {
                        val listSize = if (newCtx.overBudget) 0 else Arb.int(cfg[ListValueSize]).next(rs)
                        val values = buildList(listSize) {
                            repeat(listSize) {
                                add(genValue(newCtx))
                            }
                        }
                        IR.Value.List(values)
                    }
                }

                tc.type is GraphQLScalarType -> scalarGen.gen(tc.type)

                tc.type is GraphQLEnumType -> enumGen.gen(tc.type)

                tc.type is GraphQLInputObjectType && tc.type.isOneOf -> {
                    val allFields = tc.type.fields

                    // When overBudget, prefer fields that don't force further cycle recursion:
                    // non-input-object fields, list-wrapped fields (which terminate with an empty
                    // list), or input-object fields outside the host's mandatory cycle group.
                    val genFields = if (overBudget) {
                        val cycleGroup = mandatoryEdgesGraph[tc.type.name]
                        val exitFields = allFields.filter { f ->
                            val unwrapped = GraphQLTypeUtil.unwrapAll(f.type)
                            unwrapped !is GraphQLInputObjectType ||
                                GraphQLTypeUtil.unwrapNonNull(f.type) is GraphQLList ||
                                unwrapped.name !in cycleGroup
                        }
                        exitFields.ifEmpty { allFields }
                    } else {
                        allFields
                    }

                    val field = Arb.of(genFields).next(rs)
                    val fieldValue = genValue(traverse(field).copy(nonNullable = true))
                    IR.Value.Object(tc.type.name, field.name to fieldValue)
                }

                tc.type is GraphQLInputObjectType -> {
                    val cycleGroup = allEdgesGraph[tc.type.name]
                    val nameValuePairs = tc.type.fields
                        .filter { f ->
                            val fieldTypeName = (GraphQLTypeUtil.unwrapAll(f.type) as? GraphQLInputObjectType)?.name

                            if (fieldTypeName != null && fieldTypeName in cycleGroup) {
                                // The type of this field is in a cycle with the current input object type,
                                // Include these fields, even if overBudget, to ensure that the generated values are well-formed
                                true
                            } else if (!f.hasSetDefaultValue() && GraphQLTypeUtil.isNonNull(f.type)) {
                                // field is non-nullable and has no default.
                                // Keep the field to ensure that a value is generated
                                true
                            } else {
                                // If we get to this case, the field either has a default value or it is nullable.
                                // Sample ImplicitNullValueWeight to either keep or drop
                                !overBudget && !rs.sampleWeight(cfg[ImplicitNullValueWeight])
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

    companion object {
        operator fun invoke(
            schema: ViaductSchema,
            uncoercedValueWeight: Double,
            cfg: Config,
            rs: RandomSource
        ): IRGen {
            val mandatoryEdgesGraph = CycleGroups.mandatoryInputCycles(schema)
            return IRGen(
                schema,
                mandatoryEdgesGraph,
                mandatoryEdgesGraph,
                uncoercedValueWeight,
                cfg,
                rs
            )
        }
    }
}

internal operator fun IR.Value.Object.plus(entry: Pair<String, IR.Value>): IR.Value.Object = copy(fields = fields + entry)

internal class EnumValueGen(private val rs: RandomSource) {
    fun gen(type: GraphQLEnumType): IR.Value.String = Arb.of(type.values).next(rs).let { IR.Value.String(it.name) }
}

internal class ScalarValueGen(
    private val uncoercedValueWeight: Double,
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
            "Float" -> {
                // The coercion rules for Float require being able to coerce an Int to a Float value
                // Generate some Floats as Ints
                if (rs.sampleWeight(uncoercedValueWeight)) {
                    IR.Value.Number(Arb.int().next(rs))
                } else {
                    IR.Value.Number(Arb.double().next(rs))
                }
            }
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
