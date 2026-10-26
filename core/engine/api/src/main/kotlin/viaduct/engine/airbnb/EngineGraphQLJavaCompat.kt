package viaduct.engine.airbnb

import graphql.GraphQL
import viaduct.engine.api.EngineExecutionContext

/** Lives in engine-api so runtime2 does not depend on engine wiring. */
@Deprecated("Airbnb use only")
interface EngineGraphQLJavaCompat {
    fun getGraphQL(): GraphQL

    fun createEngineExecutionContext(requestContext: Any?): EngineExecutionContext
}
