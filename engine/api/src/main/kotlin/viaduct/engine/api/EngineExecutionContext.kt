package viaduct.engine.api

import graphql.schema.GraphQLObjectType

/**
 * Request-scoped execution context used to pass contextual elements to tenant API implementations
 */
interface EngineExecutionContext {
    val fullSchema: ViaductSchema
    val scopedSchema: ViaductSchema
    val activeSchema: ViaductSchema
    val requestContext: Any?
    val rawSelectionSetFactory: RawSelectionSet.Factory
    val rawSelectionsLoaderFactory: RawSelectionsLoader.Factory

    /**
     * For now, a wrapper around [rawSelectionsLoaderFactory].  Eventually will
     * probably replace that.
     * TODO(https://app.asana.com/1/150975571430/project/1208357307661305/task/1211071764227014):
     *    is this the best way to pass [resolverId] instrumentaiton data?
     */
    suspend fun query(
        resolverId: String,
        selections: RawSelectionSet
    ): EngineObjectData = rawSelectionsLoaderFactory.forQuery(resolverId).load(selections)

    /**
     * For now, a wrapper around [rawSelectionsLoaderFactory].  Eventually will
     * probably replace that.
     * TODO(https://app.asana.com/1/150975571430/project/1208357307661305/task/1211071764227014):
     *    is this the best way to pass [resolverId] instrumentaiton data?
     */
    suspend fun mutation(
        resolverId: String,
        selections: RawSelectionSet
    ): EngineObjectData = rawSelectionsLoaderFactory.forMutation(resolverId).load(selections)

    fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType,
    ): NodeReference

    // TODO(https://app.asana.com/1/150975571430/project/1203659453427089/task/1210861903745772):
    //    remove when everything has been shimmed
    fun hasModernNodeResolver(typeName: String): Boolean
}
