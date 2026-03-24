package viaduct.engine.api

/**
 * A wrapper that signals the engine to reset the resolution policy to [ResolutionPolicy.STANDARD]
 * for a subtree that would otherwise inherit [ResolutionPolicy.PARENT_MANAGED].
 *
 * This is the inverse of [ParentManagedValue]. When a parent resolver returns a [ParentManagedValue],
 * the engine sets [ResolutionPolicy.PARENT_MANAGED] for all child fields, causing registered
 * resolvers to be skipped. However, if one of those child fields contains a full resolver type
 * (e.g., a Node reference), that type's own resolvers need to execute normally.
 *
 * ## Use Case
 * When [ParentManagedValueInstrumentation][com.airbnb.viaduct.graphql.instrumentation.ParentManagedValueInstrumentation]
 * detects a full resolver type result under [ResolutionPolicy.PARENT_MANAGED], it wraps
 * the result in [StandardResolutionValue] to signal the engine to reset the policy.
 *
 * ## Behavior
 * When the engine encounters this wrapper:
 * 1. It unwraps the [value] and uses it as the source for the current level.
 * 2. It switches the [ResolutionPolicy] to [ResolutionPolicy.STANDARD] for the subtree.
 * 3. Registered resolvers for child fields execute normally.
 */
@JvmInline
value class StandardResolutionValue(val value: Any?) {
    init {
        require(value !is StandardResolutionValue)
        require(value !is ParentManagedValue)
    }
}
