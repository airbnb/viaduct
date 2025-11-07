package viaduct.engine.api

/**
 * An untyped representation of resolved GraphQL object values.  These
 * values can be nested.  For list-typed fields, the Kotlin [List]
 * type is used to represent their values.  For composite-typed fields
 * (ie, fields whose types are interface, object, or union), another
 * [EngineObjectData] is always used to represent the value of those
 * fields.  (Since these are _resolved_ values, nested fields will
 * always contain a GraphQL _object_-typed value.)
 *
 * These objects are asynchronous, meaning their fields are being
 * resolved in the background (perhaps in parallel).  Thus, an
 * attempt to read a field might suspend awaiting its value to be
 * materialized.
 *
 * These are used both to pass values into resolvers, and to return
 * values back.  The getter-functions below talk about "selections"
 * rather than "fields", because the names used to look up values
 * might be the aliases given for a field, rather than the fieldname
 * itself.  Aliasing arises for EODs passed _into_ resolvers: EODs
 * passed _out_ of resolvers _must_ use field names, not aliases.
 *
 * GraphQL objects are always "partial," meaning some of their
 * non-nullable fields do _not_ have to be set.  The [fetch] function
 * will throw an exception on attempts to read unset fields (the
 * [fetchOrNull] will return null).  This exception may be thrown
 * _after_ the caller suspends waiting for some other field(s) to be
 * resolved.  Application code generally use [fetch] to catch
 * programming bugs early, while the engine generally uses
 * [fetchOrNull] to be tolerant of fields that might not be resolved
 * due to dynamic schema changes.
 *
 * Implementations of EODs interfaces generally do _not_ do any
 * checking that either the names or types of values conform to the
 * schema of [graphQLObjectType].  In passing these objects in, the
 * engine _will_ ensure such conformance, and when passing them out,
 * Tenant API implementations _must_ ensure conformance.  (Tenant APIs
 * must _not_ assume that application code is correct and instead must
 * validate conformance.)
 */
interface EngineObjectData : EngineObject {
    /**
     * Fetch a value that was selected with the provided [selection]
     *
     * @param selection a field or alias name
     * @throws UnsetSelectionException if the selection is unset
     */
    suspend fun fetch(selection: String): Any?

    /**
     * Similar to [fetch] but returns null (rather than throws) when
     * reading unset selections.
     */
    suspend fun fetchOrNull(selection: String): Any?

    /**
     * Returns the list of selections available for this object.
     */
    suspend fun fetchSelections(): Iterable<String>

    /**
     * A synchronous version of [EngineObjectData] that provides non-suspending
     * access to field values. This is useful when working with already-resolved
     * data that doesn't require asynchronous fetching.
     */
    interface Sync : EngineObjectData {
        /**
         * Get a value that was selected with the provided [selection]
         *
         * @param selection a field or alias name
         * @throws UnsetSelectionException if the selection is unset
         */
        fun get(selection: String): Any?

        /**
         * Similar to [get] but returns null (rather than throws) when
         * reading unset selections.
         */
        fun getOrNull(selection: String): Any?

        /**
         * Get the list of selections available for this object.
         */
        fun getSelections(): Iterable<String>
    }
}
