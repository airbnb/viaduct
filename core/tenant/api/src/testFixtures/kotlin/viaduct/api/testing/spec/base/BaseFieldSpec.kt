package viaduct.api.testing.spec.base

import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi

/**
 * Base input carrier for non-batch field resolvers. Adds [queryValue], [arguments], and
 * [selections] shared by FieldResolverSpec and MutationResolverSpec.
 * Mirrors [viaduct.api.context.BaseFieldExecutionContext] in the hierarchy.
 */
@ExperimentalApi
abstract class BaseFieldSpec<Q : Query, A : Arguments> : BaseResolverSpec() {
    var queryValue: Q? = null
    var arguments: A? = null
    var selections: SelectionSet<*> = SelectionSet.NoSelections
}
