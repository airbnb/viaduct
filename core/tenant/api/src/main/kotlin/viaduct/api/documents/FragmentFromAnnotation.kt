package viaduct.api.documents

import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.ExperimentalApi

/**
 * Abstract base class for named GraphQL fragment objects.
 *
 * Extend this class with a Kotlin singleton `object` and annotate it with [GraphQLFragment] to
 * declare a reusable GraphQL fragment.
 *
 * ```kotlin
 * @GraphQLFragment("""
 *   fragment UserCoreFields on User {
 *     id
 *     name
 *     email
 *   }
 * """)
 * object UserCoreFieldsFragment : FragmentFromAnnotation<User>()
 * ```
 *
 * Spread the fragment in resolver fragments:
 * ```kotlin
 * @Resolver(objectValueFragment = "fragment _ on Account { ...UserCoreFields }")
 * class AccountDisplayNameResolver : AccountResolvers.DisplayName() { ... }
 * ```
 *
 * or in subquery operations:
 * ```kotlin
 * val result = ctx.query("{ viewer { ...UserCoreFields } }")
 * ```
 *
 * The subclass must be a Kotlin singleton `object` annotated with [GraphQLFragment].
 * Named fragments are scoped to the tenant module in which they are declared.
 *
 * @param T the GraphQL object type on which this fragment selects fields — must be a composite
 *   output type (object, interface, or union).
 *
 * @see GraphQLFragment
 * @see [Resolver][viaduct.api.resolver.Resolver]
 */
@ExperimentalApi
abstract class FragmentFromAnnotation<T : CompositeOutput> {
    /**
     * The GraphQL fragment definition text declared in [@GraphQLFragment][GraphQLFragment].
     *
     * @throws IllegalStateException if the subclass is not annotated with [@GraphQLFragment][GraphQLFragment].
     */
    val fragmentText: String by lazy {
        this::class.annotations
            .filterIsInstance<GraphQLFragment>()
            .firstOrNull()
            ?.value
            ?: error("${this::class.simpleName} must be annotated with @GraphQLFragment")
    }
}
