package viaduct.arbitrary.graphql

import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.Selection as GJSelection
import graphql.language.SelectionSet
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.ConfigKey
import viaduct.engine.api.ViaductSchema
import viaduct.mapping.graphql.IR

/**
 * Return an [Arb] that can generate an [IR.Value] for the provided output type
 * and selections.
 *
 * This generator knows how to handle these [ConfigKey]s:
 * - [ExplicitNullValueWeight]
 * - [ListValueSize]
 * - [ScalarValueOverrides]
 * - [SelectedTypeBias]
 * - [StringValueSize]
 */
fun Arb.Companion.ir(
    schema: ViaductSchema,
    type: GraphQLOutputType,
    selections: SelectionSet?,
    fragments: Map<String, FragmentDefinition> = emptyMap(),
    cfg: Config = Config.default
): Arb<IR.Value> =
    arbitrary { rs ->
        IRResultGen(schema, cfg, rs).genValue(type, selections, fragments)
    }

internal class IRResultGen(
    private val schema: ViaductSchema,
    private val cfg: Config,
    private val rs: RandomSource,
) {
    private val enumGen = EnumValueGen(rs)
    private val scalarGen = ScalarValueGen(
        // coercion is only applicable to input values -- result values should never require coercion
        uncoercedValueWeight = 0.0,
        cfg,
        rs
    )

    fun genValue(
        type: GraphQLOutputType,
        selections: SelectionSet?,
        fragments: Map<String, FragmentDefinition>,
    ): IR.Value = gen(type, selections, fragments, true)

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
        fragments: Map<String, FragmentDefinition>,
        nullable: Boolean
    ): IR.Value =
        when (type) {
            is GraphQLNonNull -> gen(GraphQLTypeUtil.unwrapOneAs(type), selections, fragments, false)
            is GraphQLList ->
                enullOr(nullable) {
                    val listSize = Arb.int(cfg[ListValueSize]).next(rs)
                    val items = List(listSize) {
                        gen(GraphQLTypeUtil.unwrapOneAs(type), selections, fragments, true)
                    }
                    IR.Value.List(items)
                }

            is GraphQLObjectType ->
                enullOr(nullable) {
                    genObject(type, requireNotNull(selections), fragments)
                }

            is GraphQLCompositeType ->
                enullOr(nullable) {
                    val concreteType = concretizeType(type, requireNotNull(selections), fragments)
                    gen(concreteType, selections, fragments, nullable)
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
        selections: SelectionSet,
        fragments: Map<String, FragmentDefinition>
    ): IR.Value.Object {
        val concreteType = concretizeType(type, selections, fragments)

        return selections.selections.fold(IR.Value.Object(concreteType.name)) { acc, sel ->
            when (sel) {
                is Field -> {
                    if (sel.name == "__typename") {
                        acc + (sel.resultKey to IR.Value.String(concreteType.name))
                    } else {
                        val fieldDef = requireNotNull(concreteType.getField(sel.name)) {
                            "unexpected field: ${concreteType.name}.${sel.name}"
                        }
                        val value = gen(fieldDef.type, sel.selectionSet, fragments, true)
                        acc + (sel.resultKey to value)
                    }
                }

                is FragmentSpread -> {
                    val fragment = requireNotNull(fragments[sel.name]) { "missing fragment `${sel.name}`" }
                    val fragmentType = schema.schema.getTypeAs<GraphQLCompositeType>(fragment.typeCondition.name)
                    if (schema.rels.isSpreadable(concreteType, fragmentType)) {
                        val fragmentResult = genObject(concreteType, fragment.selectionSet, fragments)
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
                        val fragmentResult = genObject(concreteType, sel.selectionSet, fragments)
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
        selections: SelectionSet,
        fragments: Map<String, FragmentDefinition>
    ): GraphQLObjectType {
        if (type is GraphQLObjectType) return type

        var candidateTypes = listOf<GraphQLObjectType>()
        if (rs.sampleWeight(cfg[SelectedTypeBias])) {
            val concreteCandidates = selectedObjectTypes(selections, fragments)
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
    private fun selectedObjectTypes(
        selections: SelectionSet,
        fragments: Map<String, FragmentDefinition>
    ): Set<GraphQLObjectType> {
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
                    val fragment = requireNotNull(fragments[sel.name]) {
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
