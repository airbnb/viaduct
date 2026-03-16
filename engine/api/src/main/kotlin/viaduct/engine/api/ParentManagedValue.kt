package viaduct.engine.api

/**
 * A wrapper that can be returned by a resolver to signal that it should
 * take over the resolution of its nested selections.
 *
 * Returning this wrapper enables "Parent-Managed Resolution", where the parent
 * resolver provides the full data structure for its children, bypassing individual
 * field resolvers.
 *
 * ## Use Cases
 * - **Adapters**: When wrapping legacy objects or map structures where you want to
 *   expose the raw data directly without writing trivial resolvers for every field.
 * - **Optimization**: When the parent can fetch the entire subtree more efficiently
 *   than resolving each field individually.
 *
 * ## Behavior
 * When the engine encounters this wrapper:
 * 1. It unwraps the [value] and uses it as the source for the current level.
 * 2. It switches the [ResolutionPolicy] to [ResolutionPolicy.PARENT_MANAGED] for all
 *    nested selections.
 * 3. Registered resolvers for child fields are **skipped**.
 * 4. Fields are resolved using the default property data fetcher (reading properties/keys
 *    from the [value]).
 */
@JvmInline
value class ParentManagedValue(val value: Any?) {
    init {
        require(value !is ParentManagedValue)
        require(value !is StandardResolutionValue)
    }
}
