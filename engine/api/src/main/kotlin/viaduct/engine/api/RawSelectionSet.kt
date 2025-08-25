package viaduct.engine.api

import graphql.language.Argument
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.fragment.Fragment

/**
 * An untyped selection of a [RawSelectionSet]
 *
 * @param typeCondition the type condition of this selection
 * @param fieldName the name of the GraphQL field selected by this selection
 * @param selectionName the name of [fieldName] when it was selected. Usually this is the
 * same value as fieldName, though may be different if fieldName was selected with an alias.
 */
data class RawSelection(val typeCondition: String, val fieldName: String, val selectionName: String)

/**
 * RawSelectionSet provides an untyped interface for SelectionSet manipulation. It is intended for direct
 * use by the Viaduct engine or indirect use by tenants via a [SelectionSetImpl].
 *
 * It is differentiated from the graphql-java SelectionSet class by being specialized for
 * Viaduct use-cases, including:
 * - @skip or @include directives are applied eagerly
 *
 * - operations that involve projecting from an interface into an implementation, or a union into a
 *   member, will inherit selected fields from the parent type.
 */
interface RawSelectionSet {
    /** the type condition of this selection set */
    val type: String

    /**
     * Return a list of [RawSelection]s selected in this RawSelectionSet
     *
     * The selections may include fields that are valid selections but are not defined on the
     * current [type], such as coordinates that require one or more type narrowing/widening steps.
     *
     * For example, given this schema:
     * ```graphql
     * union FooOrBar = Foo | Bar
     * type Foo { x:Int }
     * type Bar { y:Int }
     * ```
     *
     * And these selections on type `Foo`
     * ```
     * x
     * ... on FooOrBar {
     *   __typename
     *   ... on Bar { y }
     * }
     * ```
     * This method will return a [RawSelection] for `Foo.x`, `FooOrBar.__typename`, and `Bar.y`
     *
     * @see traversableSelections
     */
    fun selections(): List<RawSelection>

    /**
     * Return the [RawSelection]s immediately selected by this RawSelectionSet that can be traversed
     * using [selectionSetForField].
     *
     * For example, given this schema:
     * ```graphql
     * type Foo { x:Int, foo:Foo }
     * ```
     *
     * And these selections on type `Foo`
     * ```
     * x
     * foo { __typename }
     * ```
     * This method will return a RawSelection for the `Foo.foo` selection
     *
     * @see selections
     * @see selectionSetForField
     */
    fun traversableSelections(): List<RawSelection>

    /**
     * Render this RawSelectionSet into a graphql-java [graphql.language.SelectionSet].
     * Any skip/include directives that use variables available in this object's [RawSelectionSetContext] will be
     * applied.
     * Any fragment spreads that are used by this RawSelectionSet will be converted into inline fragments.
     * Any variable references will be included in the returned SelectionSet
     */
    fun toSelectionSet(): SelectionSet

    /** Add the provided variables to the current RawSelectionSet, checking for naming collisions */
    fun addVariables(variables: Map<String, Any?>): RawSelectionSet

    /** Render this RawSelectionSet as a [Fragment] */
    fun toFragment(): Fragment

    /**
     * Transform this RawSelectionSet into one that is on the Query type and uses the provided
     * [nodeFieldName] (ie "node") to wrap the current selections.
     *
     * A runtime exception will be thrown if the type of this RawSelectionSet is not same-or-narrower
     * than `Node`.
     */
    fun toNodelikeSelectionSet(
        nodeFieldName: String,
        arguments: List<Argument>
    ): RawSelectionSet

    /**
     * Format this RawSelectionSet as a field set without the outer parenthesis, inlining all
     * fragment spreads, and eagerly applying skip and include directives where possible.
     * Any variable references will be included in the returned String.
     *
     * The returned String will include an inline fragment and type condition, for example:
     * ```
     * ... on Foo { id @skip(if:$var) }
     * ```
     *
     * This is useful to embed in a larger selection set, provided that the host selection
     * set is known to be merge-compatible with this RawSelectionSet and is able to provide
     * any required variable definitions.
     * e.g.
     * ```
     * fragment _ on Foo {
     *    fieldA
     *    fieldB
     *    ${someRawSelectionSet.printAsFieldSet()}
     * }
     * ```
     */
    fun printAsFieldSet(): String

    /**
     * Return true if the provided coordinate is contained in this selection set,
     * even if aliased with a different selection name.
     *
     * @param type a type projection that should be applied to this selection set
     *   before checking for a selection on [field]. The provided [type] may be any
     *   type that is considered a valid fragment spread on this [RawSelectionSet]'s current type.
     *   The rules of spreadability are defined at:
     *   https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible
     * @param field the name of a GraphQL field that is defined on the provided [type].
     */
    fun containsField(
        type: String,
        field: String
    ): Boolean

    /**
     * Return true if this [RawSelectionSet] contains a selection that produces a GraphQL result key
     *   that matches the provided [selectionName], false otherwise.
     *
     * @param type a type projection that should be applied to this selection set
     *   before checking for a selection on [selectionName]. The provided [type] may be any
     *   type that is considered a valid fragment spread on this [RawSelectionSet]'s current type.
     *   The rules of spreadability are defined at:
     *   https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible
     * @param selectionName a field or alias name
     */
    fun containsSelection(
        type: String,
        selectionName: String
    ): Boolean

    /**
     * Resolve the provided selection into a [RawSelection].
     *
     * @param type a type projection that should be applied to this selection set
     *   before checking for a selection on [selectionName]. The provided [type] may be any
     *   type that is considered a valid fragment spread on this [RawSelectionSet]'s current type.
     *   The rules of spreadability are defined at:
     *   https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible
     * @param selectionName a field or alias name
     * @throws IllegalArgumentException if the given selection cannot be resolved to a field
     */
    fun resolveSelection(
        type: String,
        selectionName: String
    ): RawSelection

    /**
     * Returns true if the provided type is *requested* in this object's selections.
     *
     * *Requested* means that this selection set contains an explicit field-selection on a type
     * that is same-or-narrower than the supplied type, or that this selection set includes
     * an inline fragment or fragment spread with a type condition that is the same-or-narrower
     * than the supplied type. An empty selection set is sufficient to make the type of those
     * selections *requested*.
     *
     * This method can be used to determine if a client supports a union member or an interface
     * implementation.
     */
    fun requestsType(type: String): Boolean

    /**
     * Derive a new RawSelectionSet describing the field-subselections at the provided field
     * coordinate.
     *
     * @param type a type projection that should be applied to this selection set
     *   before checking for a selection on [field]. The provided [type] may be any
     *   type that is considered a valid fragment spread on this [RawSelectionSet]'s current type.
     *   The rules of spreadability are defined at:
     *   https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible
     * @throws IllegalArgumentException if the coordinate does not exist or does not
     *   support subselections
     */
    fun selectionSetForField(
        type: String,
        field: String
    ): RawSelectionSet

    /**
     * Derive a new RawSelectionSet describing the subselections at the provided selection
     *
     * @param type a type projection that should be applied to this selection set
     *   before checking for a selection on [selectionName]. The provided [type] may be any
     *   type that is considered a valid fragment spread on this [RawSelectionSet]'s current type.
     *   The rules of spreadability are defined at:
     *   https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible
     * @param selectionName a field or alias name
     * @throws IllegalArgumentException if the given selection cannot be resolved to a field or if the resolved
     * coordinate does not support subselections
     */
    fun selectionSetForSelection(
        type: String,
        selectionName: String
    ): RawSelectionSet

    /**
     * Return a projection of this RawSelectionSet for a provided spreadable type.
     *
     * For the details of what is considered "spreadable", see section 5.5.2.3: Fragment Spread Is Possible
     * https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible
     */
    fun selectionSetForType(type: String): RawSelectionSet

    /**
     * Return true if this RawSelectionSet is *empty*, where *empty* means that all possible
     * type conditions that may be applied to this selection set contain no fields.
     *
     * A true value indicates that executing this selection set is guaranteed to resolve no
     * fields and to return no data.
     *
     * A false value indicates that this SelectionSet may contain one or more fields that
     * will be resolved if executed.
     *
     * This method can return false for selection sets that contain fields with empty
     * sub-selections. For deeper emptiness checking, see [isTransitivelyEmpty].
     *
     * @see isTransitivelyEmpty
     */
    fun isEmpty(): Boolean

    /**
     * Return true if this RawSelectionSet is *transitively empty*, where *transitively empty*
     * means that this selection set either contains no fields or the fields it does contain
     * have sub-selections that are also *transitively empty*.
     *
     * A true value indicates that executing this selection set is guaranteed to return no
     * data, though it may resolve fields.
     *
     * A false value indicates that this selection set contains one or more fields that
     * will certainly be resolved
     *
     * @see isEmpty
     */
    fun isTransitivelyEmpty(): Boolean

    /**
     * Return a map of coerced argument values for a provided selection.
     *
     * @param type a type projection that should be applied to this selection set
     *   before checking for a selection on [selectionName]. The provided [type] may be any
     *   type that is considered a valid fragment spread on this [RawSelectionSet]'s current type.
     *   The rules of spreadability are defined at:
     *   https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible
     * @param selectionName a field or alias name
     * @return null if no selection for the provided [type] and [selectionName] exist, otherwise a map
     *   describing coerced argument values visible to the selection.
     */
    fun argumentsOfSelection(
        type: String,
        selectionName: String
    ): Map<String, Any?>?

    interface Factory {
        /** Create a RawSelectionSet */
        fun rawSelectionSet(
            typeName: String,
            selections: String,
            variables: Map<String, Any?>
        ): RawSelectionSet

        /** Create a RawSelectionSet */
        fun rawSelectionSet(
            selections: ParsedSelections,
            variables: Map<String, Any?>
        ): RawSelectionSet

        /**
         * Derive a RawSelectionSet from the supplied [DataFetchingEnvironment].
         *
         * The returned RawSelectionSet describes the selection set that will be applied
         * to the value returned by the data fetcher that was invoked with the supplied [env].
         *
         * Returns null if the type that should be returned for the supplied [env] does not
         * support selection sets.
         */
        fun rawSelectionSet(env: DataFetchingEnvironment): RawSelectionSet?
    }

    private class Empty(override val type: String) : RawSelectionSet {
        override fun containsField(
            type: String,
            field: String
        ): Boolean = false

        override fun containsSelection(
            type: String,
            selectionName: String
        ): Boolean = false

        override fun resolveSelection(
            type: String,
            selectionName: String
        ): RawSelection = throw IllegalArgumentException("Not selected: $type.$selectionName")

        override fun requestsType(type: String): Boolean = false

        override fun selectionSetForField(
            type: String,
            field: String
        ): RawSelectionSet = Empty(type)

        override fun selectionSetForSelection(
            type: String,
            selectionName: String
        ): RawSelectionSet = Empty(type)

        override fun selectionSetForType(type: String): RawSelectionSet = this

        override fun isEmpty(): Boolean = true

        override fun isTransitivelyEmpty(): Boolean = true

        override fun argumentsOfSelection(
            type: String,
            selectionName: String
        ): Map<String, Any?>? = null

        override fun selections(): List<RawSelection> = emptyList()

        override fun traversableSelections(): List<RawSelection> = emptyList()

        override fun toSelectionSet(): SelectionSet = SelectionSet(emptyList())

        override fun addVariables(variables: Map<String, Any?>): RawSelectionSet = throw UnsupportedOperationException("addVariables is not supported for RawSelectionSet.Empty")

        override fun toFragment(): Fragment = Fragment.empty

        override fun toNodelikeSelectionSet(
            nodeFieldName: String,
            arguments: List<Argument>
        ): RawSelectionSet = throw UnsupportedOperationException("toNodelikeSelectionSet is not supported for RawSelectionSet.Empty")

        override fun printAsFieldSet(): String = ""
    }

    companion object {
        /** create a [RawSelectionSet] for a provided type that contains no selections */
        fun empty(name: String): RawSelectionSet = Empty(name)
    }
}
