package viaduct.engine.api

import viaduct.apiannotations.InternalApi

/**
 * The resolver that caused an execution to run.
 *
 * Tenant code sees this data as `viaduct.api.context.Caller`.
 *
 * @property tenantName The resolver's tenant. Null when the resolver has no tenant metadata.
 * @property typeName The GraphQL type: a field resolver's parent type, or a node resolver's type.
 * @property fieldName The GraphQL field that a field resolver handles. Null for a node resolver,
 *   which resolves a whole type.
 */
@InternalApi
data class Caller(
    val tenantName: String?,
    val typeName: String,
    val fieldName: String?,
)
