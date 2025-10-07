package viaduct.graphql.utils

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import viaduct.utils.collections.MaskedSet

/**
 * GraphQLTypeRelations computes relationships between GraphQL types.
 * @see [GraphQLTypeRelation.Relation]
 */
class GraphQLTypeRelations(schemaTypes: List<GraphQLType>) {
    constructor(schema: GraphQLSchema) : this(schema.allTypesAsList)

    // parent interface/union type -> child object/interface types
    private val possibleTypes: MutableMap<GraphQLCompositeType, Set<GraphQLCompositeType>> = mutableMapOf()
    private val possibleObjectTypes: Map<GraphQLCompositeType, MaskedSet<GraphQLObjectType>>
    private val spreadableTypes: MutableMap<GraphQLCompositeType, Set<GraphQLCompositeType>> = mutableMapOf()

    // time-wise, it is more efficient to compute possible types for all types up front rather than doing them
    // individually per request
    // This might be memory-intensive for large schemas, in which case this decision should be reconsidered
    init {
        // graphql-java doesn't have an efficient API for looking up which unions an object type belongs to.
        // In order to compute possible abstract-abstract spreads, create a temporary mapping of concrete
        // objects to their abstract parents
        val objectToAbstractTypes: MutableMap<GraphQLObjectType, Set<GraphQLCompositeType>> = mutableMapOf()

        fun <Key, Value> addEntry(
            map: MutableMap<Key, Set<Value>>,
            key: Key,
            value: Value
        ) = map.put(key, map[key]?.plus(value) ?: setOf(value))

        schemaTypes.forEach { t ->
            if (t is GraphQLCompositeType) {
                // every composite type is always spreadable with itself
                addEntry(spreadableTypes, t, t)
                addEntry(possibleTypes, t, t)
            }

            when (t) {
                is GraphQLObjectType -> {
                    val interfaces = collectInterfaces(t)
                    interfaces.forEach { i ->
                        addEntry(objectToAbstractTypes, t, i)

                        // an object is mutually spreadable with all of its interfaces
                        addEntry(spreadableTypes, i, t)
                        addEntry(spreadableTypes, t, i)

                        // collect all declared and implied interfaces implemented by t
                        // foreach interface i, add t as a possible type
                        addEntry(possibleTypes, i, t)
                    }
                }

                // the schema may contain disconnected interfaces without any concrete implementations.
                // This is partly redundant with the block that handles GraphQLObjectType, but is important
                // to do for completeness
                is GraphQLInterfaceType -> {
                    // collect all parent interfaces implemented by this interface type t
                    // foreach parent interface i, add t as a possible type
                    collectInterfaces(t).forEach {
                        addEntry(possibleTypes, it, t)
                    }
                }

                is GraphQLUnionType -> {
                    t.types.forEach { member ->
                        // member is typed as a GraphQLNamedOutputType, which allows type references
                        // to be used before the schema is completely resolved
                        // We can assume that we have a completely resolved schema and that this cast is safe.
                        member as GraphQLObjectType
                        addEntry(possibleTypes, t, member)

                        // an object is mutually spreadable with any union that it is a member of
                        addEntry(spreadableTypes, t, member)
                        addEntry(spreadableTypes, member, t)

                        // graphql-java doesn't have any ways of looking up all possible unions that contain an object.
                        // record this in a temporary map so that we can update spreadable types in a second pass.
                        addEntry(objectToAbstractTypes, member, t)
                    }
                }

                else -> {}
            }
        }

        // add abstract-abstract spreads for interfaces and unions
        objectToAbstractTypes.forEach { (_, abstracts) ->
            abstracts.forEach { a1 ->
                abstracts.forEach { a2 ->
                    addEntry(spreadableTypes, a1, a2)
                    addEntry(spreadableTypes, a2, a1)
                }
            }
        }

        possibleObjectTypes = possibleTypes.mapValues { (_, values) ->
            MaskedSet(values.filterIsInstance<GraphQLObjectType>())
        }.toMap()
    }

    private fun collectInterfaces(type: GraphQLImplementingType): Set<GraphQLInterfaceType> {
        val self = if (type is GraphQLInterfaceType) setOf(type) else emptySet()
        return self +
            type.interfaces
                // `interfaces` is a GraphQLNamedOutputType because it might be a type reference that
                // has not been resolved yet. We can ignore these since we expect type references to be resolved
                // before this code runs
                .map { it as GraphQLInterfaceType }
                .toSet()
    }

    /**
     * Return the set of types which can be used as type condition within a selection set of [parentType]
     */
    fun spreadableTypes(parentType: GraphQLCompositeType): Set<GraphQLCompositeType> =
        requireNotNull(spreadableTypes[parentType]) {
            "Invariant: empty spreadable types for ${parentType.name}"
        }

    /**
     * Return a boolean describing if the provided fragmentType may be spread in the scope of
     * the provided parentType
     *
     * This behavior is formally defined in the spec at:
     *   https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible.Formal-Specification
     *
     * For reference, graphql-java encodes this algorithm as a validation rule, PossibleFragmentSpreads:
     *   https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/validation/rules/PossibleFragmentSpreads.java
     *
     * Some interesting fragment spread cases that are allowed by the spec
     * include (arrows point in direction of allowed spreads, some of these are not obviously allowed):
     *   - union <-> member object
     *   - interface <-> implementing object
     *   - interface <-> implementing interface
     *   - type x <-> the same type x
     *   - interface <-> union, provided that 1+ union members implement that interface
     *   - interface <-> other interface, provided the schema contains 1+ objects that implements both interfaces
     */
    fun isSpreadable(
        parentType: GraphQLCompositeType,
        fragmentType: GraphQLCompositeType
    ): Boolean {
        val unwrappedParent = GraphQLTypeUtil.unwrapAllAs<GraphQLCompositeType>(parentType)
        val unwrappedFragment = GraphQLTypeUtil.unwrapAllAs<GraphQLCompositeType>(fragmentType)
        require(spreadableTypes.containsKey(unwrappedParent)) { "Unknown type: ${unwrappedParent.name} " }
        require(spreadableTypes.containsKey(unwrappedFragment)) { "Unknown type: ${unwrappedFragment.name} " }

        return spreadableTypes(unwrappedParent).contains(unwrappedFragment)
    }

    /**
     * Return the [GraphQLTypeRelation.Relation] describing how [a] is related to [b]
     */
    fun relationUnwrapped(
        a: GraphQLCompositeType,
        b: GraphQLCompositeType
    ): GraphQLTypeRelation.Relation {
        if (a == b) return GraphQLTypeRelation.Same

        val aChildren = requireNotNull(possibleTypes[a]) { "Unknown type: ${a.name}" }
        if (aChildren.contains(b)) {
            return GraphQLTypeRelation.WiderThan
        }

        val bChildren = requireNotNull(possibleTypes[b]) { "Unknown type: ${b.name}" }
        if (bChildren.contains(a)) {
            return GraphQLTypeRelation.NarrowerThan
        }

        // if types are spreadable but not same/widerthan/narrowerthan, then they must be coparents
        if (isSpreadable(a, b)) {
            return GraphQLTypeRelation.Coparent
        }

        return GraphQLTypeRelation.None
    }

    /**
     * Return a [GraphQLTypeRelation.Relation] describing how [a] is related to [b], after removing
     * any List or NonNull type wrappers.
     *
     * While relationships are only defined for [GraphQLCompositeType]'s, the provided types
     * may be any valid GraphQL type.
     */
    fun relation(
        a: GraphQLType,
        b: GraphQLType
    ): GraphQLTypeRelation.Relation {
        val au = GraphQLTypeUtil.unwrapAll(a)
        val bu = GraphQLTypeUtil.unwrapAll(b)

        if (au == bu) {
            return GraphQLTypeRelation.Same
        } else if (au is GraphQLCompositeType && bu is GraphQLCompositeType) {
            return relationUnwrapped(au, bu)
        }

        return GraphQLTypeRelation.None
    }

    /** Return all possible concrete object types that may be an instance of the provided type */
    fun possibleObjectTypes(type: GraphQLCompositeType): MaskedSet<GraphQLObjectType> = possibleObjectTypes[type]!!
}

/** A relationship between 2 GraphQL types */
object GraphQLTypeRelation {
    interface Relation

    /** The same type */
    object Same : Relation

    /** An interface with an implementation of, or a union whose member types include */
    object WiderThan : Relation

    /** An implementation of an interface, or a member of a union */
    object NarrowerThan : Relation

    /**
     * Types are related through a common [NarrowerThan] type.
     *
     * For more on why this is useful, see
     * https://spec.graphql.org/draft/#sec-Abstract-Spreads-in-Abstract-Scope
     */
    object Coparent : Relation

    /** No relationship */
    object None : Relation
}
