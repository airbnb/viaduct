package viaduct.mapping.test

import graphql.introspection.Introspection
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.double
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
import viaduct.arbitrary.common.WeightValidator
import viaduct.arbitrary.graphql.ExplicitNullValueWeight
import viaduct.arbitrary.graphql.ImplicitNullValueWeight
import viaduct.arbitrary.graphql.ListValueSize
import viaduct.arbitrary.graphql.MaxValueDepth
import viaduct.arbitrary.graphql.TypenameValueWeight
import viaduct.arbitrary.graphql.sampleWeight
import viaduct.arbitrary.graphql.weightedChoose
import viaduct.mapping.graphql.IR

/**
 * Return an [Arb] that can generate an [IR.Value.Object] for
 * an input or output type in the provided schema.
 *
 * @param cfg Configuration to shape the generated value. This method knows how to handle these [ConfigKey]s:
 *   - [OutputObjectValueWeight]
 *   - [InputObjectValueWeight]
 *   - [IntrospectionObjectValueWeight]
 *   - [ValueTransform]
 *   - [ListValueSize]
 *   - [ImplicitNullValueWeight]
 *   - [ExplicitNullValueWeight]
 *   - [MaxValueDepth]
 *   - [TypenameValueWeight]
 */
fun Arb.Companion.objectIR(
    schema: GraphQLSchema,
    cfg: Config = Config.default
): Arb<IR.Value.Object> =
    arbitrary { rs ->
        IRGen(rs, schema, cfg).genObjectValue()
    }

/**
 * Return an [Arb] that can generate an [IR.Value.Object] for arbitrary output objects
 * in the provided schema.
 *
 * @param cfg see docs for [Arb.Companion.objectIR] for a list of support [ConfigKey]s
 */
fun Arb.Companion.outputObjectIR(
    schema: GraphQLSchema,
    cfg: Config = Config.default
): Arb<IR.Value.Object> = objectIR(schema, cfg + (OutputObjectValueWeight to 1.0) + (InputObjectValueWeight to 0.0))

/**
 * Return an [Arb] that can generate an [IR.Value.Object] for arbitrary input objects
 * in the provided schema.
 *
 * @param cfg see docs for [Arb.Companion.objectIR] for a list of support [ConfigKey]s
 */
fun Arb.Companion.inputObjectIR(
    schema: GraphQLSchema,
    cfg: Config = Config.default
): Arb<IR.Value.Object> = objectIR(schema, cfg + (OutputObjectValueWeight to 0.0) + (InputObjectValueWeight to 1.0))

/**
 * Return an [Arb] that can generate an [IR.Value] for the provided type defined in the
 * provided schema.
 *
 * @param cfg see docs for [Arb.Companion.objectIR] for a list of support [ConfigKey]s
 */
fun Arb.Companion.ir(
    schema: GraphQLSchema,
    type: GraphQLType,
    cfg: Config = Config.default
): Arb<IR.Value> =
    arbitrary { rs ->
        IRGen(rs, schema, cfg).genValue(type)
    }

private class IRGen(
    private val rs: RandomSource,
    private val schema: GraphQLSchema,
    private val cfg: Config
) {
    private data class Ctx(
        val type: GraphQLType,
        val depth: Int,
        val maxDepth: Int,
        val nonNullable: Boolean = false,
    ) {
        fun push(type: GraphQLType): Ctx = copy(type = type, depth = depth + 1, nonNullable = type is GraphQLNonNull)

        val nullable: Boolean get() = !nonNullable
        val overBudget: Boolean get() = depth > maxDepth
    }

    private val graphQLObjectishTypeArb: Arb<GraphQLType>

    init {
        val nonIntrospectionObjectTypes = mutableListOf<GraphQLObjectType>()
        val introspectionObjectTypes = mutableListOf<GraphQLObjectType>()
        val nonIntrospectionInputObjectTypes = mutableListOf<GraphQLInputObjectType>()

        schema.allTypesAsList.forEach { t ->
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

    fun genValue(type: GraphQLType): IR.Value = genValue(Ctx(type = type, depth = 0, maxDepth = cfg[MaxValueDepth]))

    private fun genValue(ctx: Ctx): IR.Value =
        with(ctx) {
            when {
                type is GraphQLNonNull -> {
                    genValue(ctx.copy(type = type.wrappedType, nonNullable = true))
                }

                ctx.nullable && (overBudget || rs.sampleWeight(cfg[ExplicitNullValueWeight])) -> {
                    IR.Value.Null
                }

                type is GraphQLList -> {
                    val newCtx = push(type.wrappedType)
                    // return early if overbudget
                    val listSize = if (newCtx.overBudget) 0 else Arb.int(cfg[ListValueSize]).next(rs)
                    val values = buildList(listSize) {
                        repeat(listSize) {
                            add(genValue(newCtx))
                        }
                    }
                    IR.Value.List(values)
                }

                type is GraphQLScalarType -> genScalar(type)

                type is GraphQLEnumType ->
                    Arb.of(type.values).next(rs).let { IR.Value.String(it.name) }

                type is GraphQLInputObjectType -> {
                    val nameValuePairs = type.fields
                        .filterNot { f ->
                            val canInull = f.hasSetDefaultValue() || GraphQLTypeUtil.isNullable(f.type)
                            canInull && rs.sampleWeight(cfg[ImplicitNullValueWeight])
                        }
                        .map { f -> f.name to genValue(push(f.type)) }
                    IR.Value.Object(type.name, nameValuePairs.toMap())
                }

                type is GraphQLObjectType -> {
                    val fieldValues = type.fields
                        .filterNot { overBudget || rs.sampleWeight(cfg[ImplicitNullValueWeight]) }
                        .associate { f -> f.name to genValue(push(f.type)) }

                    val typenameValue = if (rs.sampleWeight(cfg[TypenameValueWeight])) {
                        mapOf("__typename" to IR.Value.String(type.name))
                    } else {
                        emptyMap()
                    }

                    IR.Value.Object(type.name, fieldValues + typenameValue)
                }

                type is GraphQLUnionType -> {
                    val member = Arb.of(type.types).next(rs)
                    genValue(ctx.copy(member))
                }

                type is GraphQLInterfaceType -> {
                    val impl = Arb.of(schema.getImplementations(type)).next(rs)
                    genValue(ctx.copy(impl))
                }

                else -> throw UnsupportedOperationException("Unsupported type: $type")
            }
        }

    private fun genScalar(type: GraphQLScalarType): IR.Value =
        when (type.name) {
            "BackingData" -> TODO("https://app.asana.com/1/150975571430/project/1211295233988904/task/1211525978501301")
            "Boolean" -> IR.Value.Boolean(Arb.boolean().next(rs))
            "Byte" -> IR.Value.Number(Arb.byte().next(rs))
            "Date" -> IR.Value.Time(Arb.localDate().next(rs))
            "DateTime" -> IR.Value.Time(Arb.instant().next(rs))
            "Float" -> IR.Value.Number(Arb.double().next(rs))
            "ID" -> IR.Value.String(Arb.string().next(rs))
            "Int" -> IR.Value.Number(Arb.int().next(rs))
            "JSON" -> IR.Value.String("{}")
            "Long" -> IR.Value.Number(Arb.long().next(rs))
            "Short" -> IR.Value.Number(Arb.short().next(rs))
            "String" -> IR.Value.String(Arb.string().next(rs))
            "Time" ->
                Arb.bind(Arb.localTime(), Arb.zoneOffset(), OffsetTime::of)
                    .next(rs)
                    .let(IR.Value::Time)
            else -> throw UnsupportedOperationException("Unsupported scalar type: ${type.name}")
        }
}

/**
 * The relative weight that when an object value is generated, that the concrete type will
 * be drawn from the pool of output object types.
 *
 * A 0.0 value means that no output object values will ever be generated, while a greater than 0
 * means that the output object type pool will be used at least some of the time.
 *
 * This weight is balanced against the value of [InputObjectValueWeight]
 */
object OutputObjectValueWeight : ConfigKey<Double>(1.0, WeightValidator)

/**
 * The relative weight that when an object value is generated, that the concrete type will
 * be drawn from the pool of input object types.
 *
 * A 0.0 value means that no input object values will ever be generated, while a greater than 0
 * means that the input object type pool will be used at least some of the time.
 *
 * This weight is balanced against the value of [OutputObjectValueWeight]
 */
object InputObjectValueWeight : ConfigKey<Double>(1.0, WeightValidator)

/** The probability that any generated object value will be for an introspection type */
object IntrospectionObjectValueWeight : ConfigKey<Double>(0.0, WeightValidator)
