package viaduct.engine.api

/**
 * Defines the policy for resolving nested fields of an object.
 *
 * This policy controls whether the engine looks up and executes registered resolvers
 * for fields, or whether it relies on simple property access on the parent object.
 */
enum class ResolutionPolicy {
    /**
     * Standard resolution: The engine looks for and executes registered field resolvers.
     * If no resolver is found, it falls back to the default property data fetcher.
     */
    STANDARD,

    /**
     * Parent-managed resolution: The parent object has explicitly taken responsibility
     * for its child fields (typically by returning a [ParentManagedValue]).
     *
     * In this mode:
     * - Registered resolvers are **ignored**.
     * - Fields are resolved purely via the default data fetcher wired for fields, typically
     *   a `PropertyDataFetcher`.
     */
    PARENT_MANAGED
}
