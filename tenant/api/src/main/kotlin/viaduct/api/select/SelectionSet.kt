package viaduct.api.select

import kotlin.reflect.KClass
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.StableApi

/**
 * SelectionSet describes a type-safe interface to querying and transforming a
 * GraphQL selection set.
 */
@StableApi
interface SelectionSet<T : CompositeOutput> {
    /**
     * Returns true if this SelectionSet includes the provided [Field].
     *
     * The provided field may describe a field on the current GraphQL type, or on a
     * member type if the current type is a union, or on an implementing type if
     * the current type is an interface.
     *
     * **Examples**
     *
     * Given this schema:
     * ```
     *   interface Node { id: ID! }
     *   type Foo implements Node { id: ID! }
     * ```
     *
     * And given these selections on Node:
     * ```
     *   ... on Foo { id }
     * ```
     *
     * Then:
     * - `contains(Foo.id)` returns true because the selections include an inline
     *   fragment on Foo that includes the id field
     *
     * - `contains(Node.id)` returns false because the selections do not include
     *   a selection on Node.id that is not guarded by a type condition.
     */
    fun <U : T> contains(field: Field<U>): Boolean

    /**
     * Returns true if the provided type is *requested* in this SelectionSet.
     *
     * *Requested* means that this SelectionSet contains an explicit field selection
     * on a type that is the same or narrower than the supplied type, or that this
     * SelectionSet includes an inline fragment or fragment spread with a type condition
     * that is the same or narrower than the supplied type.
     *
     * If a type is *requested*, it is safe to conclude that the GraphQL client that created
     * this SelectionSet knows how to handle the supplied type.
     *
     * **GraphQL Object Type Examples**
     *
     * Given this type definition:
     * ```
     *   type Foo { id: ID! }
     * ```
     * Then `requestsType(Foo)` will return these values for the provided selections
     * - for selections `id`, returns true because the selection set contains a field
     *   defined by Foo
     * - for selections `... { id }`, returns true because the selection set contains an
     *   inline fragment with an implied type condition on the surrounding type, Foo
     * - for selections `id @skip(if:true)`, returns true because the selection set
     *   itself describes an inline fragment on type Foo, even though the selections are
     *   empty.
     *
     * **GraphQL Union Type Examples**
     *
     * Given these definitions:
     * ```
     *  type Foo { id: ID! }
     *  type Bar { id: ID! }
     *  union FooOrBar = Foo | Bar
     * ```
     * And given these selections on `FooOrBar`:
     * ```
     *   ... on Foo { id }
     * ```
     *
     * Then this method will have the following behavior:
     * - `requestsType(FooOrBar)` returns true because the selections include an
     *   inline fragment on Foo, which is narrower than FooOrBar.
     * - `requestsType(Bar)` returns false because there are no selections with
     *   a type condition on Bar
     *
     * While the above example is based on a union type and its members, the rules
     * apply equally well to an interface and its implementations.
     */
    fun <U : T> requestsType(type: Type<U>): Boolean

    /**
     * Return a SelectionSet describing the selections at the provided field.
     * If the current SelectionSet does not include the provided field, then an
     * empty SelectionSet will be returned.
     *
     * This method can be used to extract selections for a field belonging to any subtype
     * of the current type. This allows deriving subselections of a field from a union
     * member if the current selections are on a union type, or deriving subselections
     * of an interface implementation if the current selections are on an interface type.
     *
     * **Examples**
     *
     * Given these type definitions:
     * ```
     *   interface Node { id: ID! }
     *   type Foo implements Node { id: ID!, bar: Bar }
     *   type Bar implements Node { id: ID!, foo: Foo }
     * ```
     * And these selections on Node:
     * ```
     *   ... on Foo {
     *     bar {
     *       id
     *       foo { id }
     *     }
     *   }
     *   ... on Foo {
     *     bar { __typename }
     *   }
     * ```
     * Then `selectionSetFor` will have the following behavior:
     * - `selectionSetFor(Foo.bar)` will return a `SelectionSet<Bar>` with a selections for
     *   `id, foo { id }, __typename`, because these are the merged selections for the
     *   Foo.bar field under Node.
     * - `selectionSetFor(Bar.foo)` will return a `SelectionSet<Foo>` that is empty, because
     *   even though Bar.foo is selected somewhere in the SelectionSet, it is not included
     *   in the immediate children of the current selections.
     */
    fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R>

    /**
     * Return a SelectionSet that is a projection of the selections on the provided type.
     * The returned SelectionSet may include fields that are selected on a parent interface
     * or union -- if you need to know if a projected SelectionSet contains non-inherited
     * selections for a type, see [requestsType]
     *
     * **Examples**
     *
     * Given these type definitions:
     * ```
     *   interface Node { id: ID! }
     *   type Foo implements Node { id: ID!, name: String }
     *   type Bar implements Node { id: ID! }
     * ```
     * And these selections on Node:
     * ```
     *   id
     *   ... on Foo { name }
     * ```
     *
     * Then `selectionSetFor` will have the following behavior:
     * - `selectionSetFor(Node)` returns the current SelectionSet because the
     *   provided type is the same as the current type
     * - `selectionSetFor(Foo)` returns a SelectionSet<Foo> with selections `id name`,
     *   because the projection for type Foo includes the selections on the parent
     *   interface Node and the inline fragment selections on Foo
     * - `selectionSetFor(Bar)` returns a SelectionSet<Bar> with selections on `id`,
     *   because the projection for type Bar includes the selections on the parent
     *   interface Node
     */
    fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U>

    /**
     * Returns true if this SelectionSet contains no fields for any valid type
     * projection.
     */
    fun isEmpty(): Boolean

    /** the type condition of this SelectionSet */
    val type: Type<T>

    companion object {
        /** Create a [SelectionSet] for a provided [Type] that contains no selections */
        fun <T : CompositeOutput> empty(type: Type<T>): SelectionSet<T> =
            object : SelectionSet<T> {
                override fun <U : T> contains(field: Field<U>): Boolean = false

                override fun <U : T> requestsType(type: Type<U>): Boolean = false

                override fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> = empty(field.type)

                override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> = empty(type)

                override fun isEmpty(): Boolean = true

                override val type: Type<T> = type
            }
    }

    /**
     * A marker object that defines a [SelectionSet] for a type that does not support selections.
     * NoSelections can be used in places where a SelectionSet is required but cannot be defined.
     */
    object NoSelections : SelectionSet<CompositeOutput.NotComposite> {
        override fun <U : CompositeOutput.NotComposite> contains(field: Field<U>): Boolean = false

        override fun <U : CompositeOutput.NotComposite> requestsType(type: Type<U>): Boolean = false

        override fun <U : CompositeOutput.NotComposite, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> =
            throw UnsupportedOperationException("NoSelections does not support extracting subselections for a field")

        override fun <U : CompositeOutput.NotComposite> selectionSetFor(type: Type<U>): SelectionSet<U> =
            throw UnsupportedOperationException("NoSelections does not support extracting subselections for a field")

        override fun isEmpty(): Boolean = true

        override val type = object : Type<CompositeOutput.NotComposite> {
            override val name: String = "__NotComposite"
            override val kcls: KClass<out CompositeOutput.NotComposite> = CompositeOutput.NotComposite::class
        }
    }
}
