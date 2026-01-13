package viaduct.arbitrary.graphql

import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeUtil
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.instant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.short
import io.kotest.property.arbitrary.string
import kotlin.random.nextInt
import viaduct.arbitrary.common.Config
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.GraphQLTypeRelations
import viaduct.mapping.graphql.RawENull
import viaduct.mapping.graphql.RawEnum
import viaduct.mapping.graphql.RawINull
import viaduct.mapping.graphql.RawInput
import viaduct.mapping.graphql.RawList
import viaduct.mapping.graphql.RawObject
import viaduct.mapping.graphql.RawScalar
import viaduct.mapping.graphql.RawValue
import viaduct.mapping.graphql.ValueMapper

/**
 * Base interface for object that can generate a value for a
 * provided `Type`, within a specified Value domain
 *
 * @see viaduct.mapping.graphql.ValueMapper
 */
interface ValueGen<Type, Value> : Function1<Type, Value> {
    private class Mapped<Type, Value, NewValue>(
        val gen: ValueGen<Type, Value>,
        val mapper: ValueMapper<Type, Value, NewValue>
    ) : ValueGen<Type, NewValue> {
        override fun invoke(type: Type): NewValue = mapper(type, gen(type))
    }

    private class Fn<Type, Value>(
        val fn: Function1<Type, Value>
    ) : ValueGen<Type, Value> {
        override fun invoke(type: Type): Value = fn(type)
    }

    /** Return a new [ValueGen] with values transformed by the provided [ValueMapper] */
    fun <NewValue> map(mapper: ValueMapper<Type, Value, NewValue>): ValueGen<Type, NewValue> = Mapped(this, mapper)

    /**
     * Return a new [ValueGen] that memoizes based on [Type]
     * The returned ValueGen is not thread-safe.
     */
    fun memoized(): ValueGen<Type, Value> =
        let { underlying ->
            val memo = mutableMapOf<Type, Value>()
            mk { t: Type ->
                memo.computeIfAbsent(t) {
                    underlying(t)
                }
            }
        }

    companion object {
        /** Construct a [ValueGen] from a provided function */
        fun <Type, Value> mk(fn: (Type) -> Value): ValueGen<Type, Value> = Fn(fn)
    }
}

/**
 * A set of [Arb]s for generating scalar RawValues, which can be initialized
 * once and rendered using multiple [RandomSource]'s .
 */
internal class ScalarRawValueArbs(
    val cfg: Config
) {
    private val stringGen = Arb.string(cfg[StringValueSize])

    private val builtins = mapOf(
        "Boolean" to Arb.boolean(),
        "Date" to Arb.localDate(),
        "DateTime" to Arb.instant(),
        "Float" to Arb.double(includeNonFiniteEdgeCases = false),
        "ID" to stringGen,
        "Int" to Arb.int(),
        "Long" to Arb.long(),
        "Short" to Arb.short(),
        "String" to stringGen
    )

    operator fun invoke(
        typename: String,
        rs: RandomSource
    ): RawValue {
        val arb = cfg[ScalarValueOverrides][typename]
            ?: builtins[typename]
            ?: throw IllegalArgumentException("Cannot generate value for scalar type: $typename")
        return RawScalar(typename, arb.next(rs))
    }
}

internal class ScalarRawValueGen(
    val cfg: Config,
    val rs: RandomSource
) : ValueGen<String, RawValue> {
    private val arbs = ScalarRawValueArbs(cfg)

    override fun invoke(typename: String): RawValue = arbs(typename, rs)
}

private abstract class GenCtx {
    abstract val depthBudget: Int
    abstract val input: Boolean
    abstract val nullable: Boolean
    abstract val rs: RandomSource
    abstract val cfg: Config

    val overBudget: Boolean get() = depthBudget <= 0

    fun enullOr(fn: () -> RawValue): RawValue =
        if (overBudget && nullable) {
            RawENull
        } else if (nullable && rs.sampleWeight(cfg[ExplicitNullValueWeight])) {
            RawENull
        } else {
            fn()
        }

    protected fun inullOr(
        hasDefault: Boolean,
        fieldNullable: Boolean,
        input: Boolean,
        fn: () -> RawValue
    ): RawValue =
        // input fields are inullable if they have either a default or are nullable
        // spec: https://spec.graphql.org/draft/#sec-Input-Objects.Input-Coercion
        if (input &&
            (hasDefault || fieldNullable) &&
            (overBudget || rs.sampleWeight(cfg[ImplicitNullValueWeight]))
        ) {
            RawINull
        } // output fields are always inullable, even when non-nullable
        else if (!input &&
            (overBudget || rs.sampleWeight(cfg[ImplicitNullValueWeight]))
        ) {
            RawINull
        } else {
            fn()
        }
}

/**
 * Generates values for a provided [ViaductSchema.TypeExpr] in the [RawValue] domain
 *
 * @see RawValue
 */
class ViaductSchemaRawValueGen(
    val cfg: Config,
    val rs: RandomSource
) : ValueGen<ViaductSchema.TypeExpr, RawValue> {
    private val scalarGen = ScalarRawValueGen(cfg, rs)

    private data class Ctx(
        val type: ViaductSchema.TypeExpr,
        override val depthBudget: Int,
        val listDepth: Int,
        override val input: Boolean,
        override val rs: RandomSource,
        override val cfg: Config,
        private val forceNonNullable: Boolean = false,
    ) : GenCtx() {
        val def: ViaductSchema.TypeDef = type.baseTypeDef
        val isList: Boolean = type.isList && type.listDepth > listDepth
        override val nullable =
            if (forceNonNullable) {
                false
            } else if (isList) {
                type.nullableAtDepth(listDepth)
            } else {
                type.baseTypeNullable
            }

        fun traverseList(): Ctx = copy(listDepth = listDepth + 1, forceNonNullable = false)

        fun traverseField(field: ViaductSchema.Field): Ctx = traverseType(field.type)

        fun traverseType(type: ViaductSchema.TypeExpr): Ctx = copy(type = type, depthBudget = depthBudget - 1, listDepth = 0, forceNonNullable = false)

        fun inullOr(
            field: ViaductSchema.Field,
            fn: () -> RawValue
        ): RawValue = inullOr(field.hasDefault, field.type.isNullable, input, fn)

        fun asNonNullable(): Ctx = copy(forceNonNullable = true)
    }

    override fun invoke(type: ViaductSchema.TypeExpr): RawValue =
        gen(
            Ctx(
                type = type,
                depthBudget = cfg[MaxValueDepth],
                listDepth = 0,
                input = type.baseTypeDef is ViaductSchema.Input,
                rs = rs,
                cfg = cfg
            )
        )

    private fun gen(c: Ctx): RawValue =
        if (c.isList) {
            c.enullOr {
                if (c.overBudget) {
                    RawList.empty
                } else {
                    rs.random.nextInt(cfg[ListValueSize]).let { size ->
                        RawList(
                            (0..size)
                                .map {
                                    gen(c.traverseList())
                                }.toList()
                        )
                    }
                }
            }
        } else if (c.def is ViaductSchema.Scalar) {
            c.enullOr {
                scalarGen(c.def.name)
            }
        } else if (c.def is ViaductSchema.Enum) {
            c.enullOr {
                RawEnum(
                    Arb.of(c.def.values.toList()).next(rs).name
                )
            }
        } else if (c.def is ViaductSchema.Input) {
            if (c.def.hasAppliedDirective("oneOf")) {
                val field = Arb.element(c.def.fields.toList()).next(rs)
                RawInput(
                    listOf(field.name to gen(c.traverseField(field).asNonNullable()))
                )
            } else {
                c.enullOr {
                    RawInput(
                        c.def.fields.map { field ->
                            val fieldValue = c.inullOr(field) {
                                gen(c.traverseField(field))
                            }
                            fieldValue.let { field.name to it }
                        }
                    )
                }
            }
        } else if (c.def is ViaductSchema.Object) {
            RawObject(
                c.def.name,
                c.def.fields.map { field ->
                    val fieldValue = c.inullOr(field) {
                        gen(c.traverseField(field))
                    }
                    fieldValue.let { field.name to it }
                }
            )
        } else if (c.def.isComposite) {
            Arb
                .of(c.def.possibleObjectTypes)
                .next(rs)
                .let { gen(c.traverseType(it.asTypeExpr())) }
        } else {
            throw IllegalArgumentException("cannot gen value for type ${c.type}")
        }
}

/** Generates values for a provided [GraphQLInputType] in the [RawValue] domain */
class GJRawValueGen(
    val resolver: TypeReferenceResolver,
    val cfg: Config,
    val rs: RandomSource
) : RawValue.DSL(),
    ValueGen<GraphQLInputType, RawValue> {
    private val scalarGen = ScalarRawValueGen(cfg, rs)

    private data class Ctx(
        val type: GraphQLInputType,
        override val depthBudget: Int,
        val listDepth: Int,
        override val rs: RandomSource,
        override val cfg: Config,
        override val nullable: Boolean
    ) : GenCtx() {
        override val input = true

        fun traverseList(): Ctx = copy(type = GraphQLTypeUtil.unwrapOneAs(type), listDepth = listDepth + 1, nullable = true)

        fun traverseField(field: GraphQLInputObjectField): Ctx = traverseType(field.type)

        fun traverseType(type: GraphQLType): Ctx =
            copy(
                type = GraphQLTypeUtil.unwrapNonNull(type) as GraphQLInputType,
                depthBudget = depthBudget - 1,
                listDepth = 0,
                nullable = GraphQLTypeUtil.isNullable(type)
            )

        fun asNonNullable(): Ctx = copy(nullable = false)

        fun inullOr(
            field: GraphQLInputObjectField,
            fn: () -> RawValue
        ): RawValue = inullOr(field.hasSetDefaultValue(), GraphQLTypeUtil.isNullable(field.type), true, fn)
    }

    override fun invoke(type: GraphQLInputType): RawValue = gen(Ctx(type, cfg[MaxValueDepth], 0, rs, cfg, nullable = true))

    private fun gen(c: Ctx): RawValue =
        when (val t = c.type) {
            is GraphQLNonNull ->
                gen(c.traverseType(t.originalWrappedType).asNonNullable())

            is GraphQLList -> {
                c.enullOr {
                    if (c.overBudget) {
                        list()
                    } else {
                        c
                            .traverseList()
                            .let { lctx ->
                                (0..rs.random.nextInt(cfg[ListValueSize])).map {
                                    gen(lctx)
                                }
                            }.let(::RawList)
                    }
                }
            }

            is GraphQLInputObjectType ->
                c.enullOr {
                    if (t.isOneOf) {
                        Arb
                            .element(t.fields)
                            .next(rs)
                            .let { f ->
                                val value = gen(c.traverseField(f).asNonNullable())
                                input(f.name to value)
                            }
                    } else {
                        t.fields
                            .map { f ->
                                f.name to c.inullOr(f) {
                                    gen(c.traverseField(f))
                                }
                            }.let(::RawInput)
                    }
                }

            is GraphQLEnumType ->
                c.enullOr {
                    Arb.of(t.values).next(rs).let { enum(it.name) }
                }

            is GraphQLTypeReference ->
                c.enullOr {
                    when (val resolved = resolver(t)) {
                        null -> {
                            if (!c.nullable) {
                                throw IllegalStateException(
                                    "Cannot generate a null value for an unresolvable reference " +
                                        "to a non-nullable ${t.name}"
                                )
                            }
                            enull
                        }

                        !is GraphQLInputType -> throw IllegalArgumentException(
                            "Cannot generate a value for a reference to non-input type ${t.name}"
                        )

                        else -> {
                            gen(c.traverseType(resolved).copy(nullable = c.nullable))
                        }
                    }
                }

            is GraphQLScalarType ->
                c.enullOr {
                    scalarGen(t.name)
                }

            else -> throw IllegalArgumentException("Cannot generate value for type $t")
        }
}

class GJRawValueResultGen(
    private val schema: GraphQLSchema,
    private val fragments: Map<String, FragmentDefinition>,
    private val rs: RandomSource,
    private val cfg: Config,
) : RawValue.DSL(),
    ValueGen<Pair<GraphQLOutputType, SelectionSet?>, RawValue> {
    private val scalarGen = ScalarRawValueGen(cfg, rs)
    private val rels = GraphQLTypeRelations(schema)

    private data class Ctx(
        val type: GraphQLOutputType,
        val selections: SelectionSet?,
        override val rs: RandomSource,
        override val cfg: Config,
        override val nullable: Boolean
    ) : GenCtx() {
        override val depthBudget: Int = Int.MAX_VALUE

        init {
            GraphQLTypeUtil.unwrapAll(type).let { unwrapped ->
                val typeStr = GraphQLTypeUtil.simplePrint(type)
                if (unwrapped is GraphQLCompositeType) {
                    checkNotNull(selections) {
                        "Selections are required for composite GraphQL type `$typeStr`"
                    }
                } else {
                    check(selections == null) {
                        "Selections not allowed for non-composite GraphQL type `$typeStr`"
                    }
                }
            }
        }

        override val input = false

        fun traverseList(): Ctx =
            GraphQLTypeUtil.unwrapOneAs<GraphQLOutputType>(type).let { unwrapped ->
                copy(type = unwrapped, nullable = GraphQLTypeUtil.isNullable(unwrapped))
            }

        fun traverseType(
            type: GraphQLType,
            nullable: Boolean = GraphQLTypeUtil.isNullable(type)
        ): Ctx =
            copy(
                type = GraphQLTypeUtil.unwrapNonNull(type) as GraphQLOutputType,
                nullable = nullable
            )

        fun traverseType(
            type: GraphQLType,
            selections: SelectionSet?,
            nullable: Boolean = GraphQLTypeUtil.isNullable(type)
        ): Ctx =
            copy(
                type = GraphQLTypeUtil.unwrapNonNull(type) as GraphQLOutputType,
                selections = selections,
                nullable = nullable
            )
    }

    override fun invoke(pair: Pair<GraphQLOutputType, SelectionSet?>): RawValue =
        gen(
            Ctx(
                type = pair.first,
                selections = pair.second,
                rs = rs,
                cfg = cfg,
                nullable = GraphQLTypeUtil.isNullable(pair.first)
            )
        )

    private fun gen(c: Ctx): RawValue =
        when (val t = c.type) {
            is GraphQLNonNull ->
                gen(c.traverseType(t.originalWrappedType, nullable = false))

            is GraphQLObjectType ->
                c.enullOr {
                    genObject(c, t, c.selections!!)
                }

            is GraphQLCompositeType -> {
                val concreteType = concretizeType(t, c.selections!!)
                gen(c.traverseType(concreteType, nullable = c.nullable))
            }

            is GraphQLList ->
                c.enullOr {
                    c.traverseList().let { lctx ->
                        buildList {
                            repeat(rs.random.nextInt(cfg[ListValueSize])) {
                                this += gen(lctx)
                            }
                        }.let(::RawList)
                    }
                }

            is GraphQLEnumType ->
                c.enullOr {
                    Arb.of(t.values).next(rs).let { enum(it.name) }
                }

            is GraphQLScalarType ->
                c.enullOr {
                    scalarGen(t.name)
                }

            else -> throw IllegalArgumentException("Cannot generate value for type $t")
        }

    private fun genObject(
        c: Ctx,
        type: GraphQLCompositeType,
        selections: SelectionSet
    ): RawObject {
        // pick a concrete object type, if needed
        val concreteType = concretizeType(type, selections)

        return selections.selections.fold(RawObject.empty(concreteType.name)) { acc, sel ->
            when (sel) {
                is Field -> {
                    if (sel.name == "__typename") {
                        acc + (sel.resultKey to RawScalar("String", acc.typename))
                    } else {
                        val fieldDef = requireNotNull(concreteType.getField(sel.name)) {
                            "unexpected field: ${concreteType.name}.${sel.name}"
                        }
                        val value = gen(c.traverseType(fieldDef.type, sel.selectionSet))
                        acc + (sel.resultKey to value)
                    }
                }

                is FragmentSpread -> {
                    val fragment = requireNotNull(fragments[sel.name]) { "missing fragment `${sel.name}`" }
                    val fragmentType = schema.getTypeAs<GraphQLCompositeType>(fragment.typeCondition.name)
                    if (rels.isSpreadable(concreteType, fragmentType)) {
                        val fragmentResult = genObject(c.traverseType(concreteType), concreteType, fragment.selectionSet)
                        acc.copy(values = acc.values + fragmentResult.values)
                    } else {
                        acc
                    }
                }

                is InlineFragment -> {
                    val fragmentType = sel.typeCondition?.name?.let { schema.getTypeAs(it) }
                        ?: c.type as GraphQLCompositeType
                    if (rels.isSpreadable(concreteType, fragmentType)) {
                        val fragmentResult = genObject(c.traverseType(concreteType), concreteType, sel.selectionSet)
                        acc.copy(values = acc.values + fragmentResult.values)
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
        selectionSet: SelectionSet
    ): GraphQLObjectType {
        if (type is GraphQLObjectType) return type

        var candidateTypes = listOf<GraphQLObjectType>()
        if (rs.sampleWeight(cfg[SelectedTypeBias])) {
            val concreteCandidates = selectedObjectTypes(selectionSet)
            if (concreteCandidates.isNotEmpty()) {
                candidateTypes = concreteCandidates.toList()
            }
        }
        if (candidateTypes.isEmpty()) {
            candidateTypes = rels.possibleObjectTypes(type).toList()
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
            pending: List<graphql.language.Selection<*>>
        ): Set<GraphQLObjectType> =
            when (val sel = pending.firstOrNull()) {
                null -> acc
                is Field -> loop(acc, pending.drop(1))
                is InlineFragment -> {
                    val typeCondition = sel.typeCondition?.name?.let { schema.getTypeAs<GraphQLCompositeType>(it) }
                    val newAcc = (typeCondition as? GraphQLObjectType)?.let { acc + it } ?: acc
                    val newPending = pending.drop(1) + sel.selectionSet.selections
                    loop(newAcc, newPending)
                }
                is FragmentSpread -> {
                    val fragment = requireNotNull(this.fragments[sel.name]) {
                        "missing fragment `${sel.name}`"
                    }
                    val typeCondition = schema.getTypeAs<GraphQLCompositeType>(fragment.typeCondition.name)
                    val newAcc = (typeCondition as? GraphQLObjectType)?.let { acc + it } ?: acc
                    val newPending = pending.drop(1) + fragment.selectionSet.selections
                    loop(newAcc, newPending)
                }
                else -> throw IllegalArgumentException("unexpected selection type: $sel")
            }

        return loop(emptySet(), selections.selections)
    }
}
